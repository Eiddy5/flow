package org.flow.gen.codegen;

import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Logging;
import org.jooq.meta.jaxb.Strategy;
import org.jooq.meta.jaxb.Target;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Explicit PostgreSQL generation entry point; normal compilation uses the checked-in output.
 */
public class JooqFlowGenerator {
    private static final String GENERATED_PACKAGE = "org.flow.gen.flow";
    private static final String URL_OPTION = "url";
    private static final String USER_OPTION = "user";
    private static final String PASSWORD_OPTION = "password";
    private static final String SCHEMA_OPTION = "schema";
    private static final String INCLUDED_TABLES = "queues|flows|executions|triggers|logs|metrics|"
        + "multipleconditions|execution_statistics|executordelayed|settings|flow_topologies|"
        + "service_instance|worker_job_running|execution_queued|sla_monitor|concurrency_limit|"
        + "kv_metadata|namespace_file_metadata|locks|task_outputs|execution_outputs|"
        + "flow_migration_history";
    private static final String INCLUDED_OBJECTS = INCLUDED_TABLES + "|state_type|log_level";

    /**
     * Generate only the Flow package using FLOW_GEN_* values, overridden by named arguments.
     */
    public static void main(String[] args) {
        Options options;
        try {
            options = Options.from(args, System.getenv());
            if (!Files.isRegularFile(Path.of("src", "codegen", "java", "org", "flow", "gen",
                "codegen", "JooqFlowGenerator.java"))) {
                throw new IllegalArgumentException("Run generation through :gen:generateJooq from the project build.");
            }
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.exit(2);
            return;
        }

        // Supply the open connection directly so JDBC secrets never enter the jOOQ configuration
        // dump or its generated configuration hash. Generation failures omit driver error text,
        // which may contain the supplied URL or authentication data.
        try (var connection = DriverManager.getConnection(options.url, options.user, options.password)) {
            validateSchema(connection, options.schema);
            var tool = new GenerationTool();
            tool.setConnection(connection);
            tool.run(configuration(options.schema));
            System.out.println("Flow jOOQ objects generated in src/main/java/org/flow/gen/flow");
        } catch (Exception exception) {
            System.err.println("Flow jOOQ generation failed (" + exception.getClass().getSimpleName()
                + "). Check the database connection, migration state and generator dependencies.");
            System.exit(1);
        }
    }

    /**
     * Refuse incomplete schemas before jOOQ can clean previously generated source files.
     */
    private static void validateSchema(Connection connection, String schema) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (var result = connection.getMetaData().getTables(null, schema, "%", null)) {
            while (result.next()) {
                if (schema.equals(result.getString("TABLE_SCHEM"))) {
                    tables.add(result.getString("TABLE_NAME"));
                }
            }
        }
        for (String table : INCLUDED_TABLES.split("\\|")) {
            if (!tables.contains(table)) {
                throw new SQLException("Required Flow table missing from generation schema.");
            }
            try (var keys = connection.getMetaData().getPrimaryKeys(null, schema, table)) {
                if (!keys.next()) {
                    throw new SQLException("Required Flow table has no primary key.");
                }
            }
        }
    }

    /**
     * 创建生成配置，默认表名交由连接的 search_path 解析，显式 rename 保留限定 schema。
     *
     * @param schema 读取元数据的 PostgreSQL schema；不能为 null 或空白
     * @return 不包含 JDBC 凭据的生成配置
     */
    static Configuration configuration(String schema) {
        return new Configuration()
            .withLogging(Logging.FATAL)
            .withGenerator(new Generator()
                .withName(FlowJavaGenerator.class.getName())
                .withStrategy(new Strategy()
                    .withName("org.jooq.codegen.ext.generator.ExtJavaGeneratorStrategy"))
                .withDatabase(new Database()
                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                    .withInputSchema(schema)
                    .withOutputSchemaToDefault(true)
                    .withIncludes(INCLUDED_OBJECTS)
                    .withIncludeRoutines(false)
                    .withIncludePackages(false)
                    .withIncludeSystemTables(false)
                    .withIncludeSystemUDTs(false))
                .withTarget(new Target()
                    .withPackageName(GENERATED_PACKAGE)
                    .withDirectory(Path.of("src", "main", "java").toString())
                    .withEncoding("UTF-8")
                    .withClean(true))
                .withGenerate(new Generate()
                    .withTables(true)
                    .withRecords(true)
                    .withPojos(true)
                    .withKeys(true)
                    .withIndexes(true)
                    .withRoutines(false)
                    .withGeneratedAnnotationDate(false)
                    .withGeneratedAnnotationConfigurationHash(false)
                    .withNewline("\n")
                    .withIndentation("    ")));
    }

    /**
     * Parse credentials without retaining arguments in diagnostic messages or a generated toString.
     */
    static class Options {
        private String url;
        private String user;
        private String password;
        private String schema;

        private Options(String url, String user, String password, String schema) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.schema = schema;
        }

        static Options from(String[] args, Map<String, String> environment) {
            Map<String, String> values = new HashMap<>();
            Set<String> allowed = Set.of(URL_OPTION, USER_OPTION, PASSWORD_OPTION, SCHEMA_OPTION);
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (!argument.startsWith("--")) {
                    throw new IllegalArgumentException("Use named --url, --user, --password or --schema arguments.");
                }
                int separator = argument.indexOf('=');
                String name = argument.substring(2, separator < 0 ? argument.length() : separator);
                if (!allowed.contains(name) || values.containsKey(name)) {
                    throw new IllegalArgumentException("Unknown or duplicate generation option.");
                }
                String value;
                if (separator >= 0) {
                    value = argument.substring(separator + 1);
                } else if (++index < args.length && !args[index].startsWith("--")) {
                    value = args[index];
                } else {
                    throw new IllegalArgumentException("Missing value for generation option.");
                }
                values.put(name, value);
            }

            String url = value(values, environment, URL_OPTION, "FLOW_GEN_URL");
            String user = value(values, environment, USER_OPTION, "FLOW_GEN_USER");
            String password = value(values, environment, PASSWORD_OPTION, "FLOW_GEN_PASSWORD");
            String schema = value(values, environment, SCHEMA_OPTION, "FLOW_GEN_SCHEMA");
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("FLOW_GEN_URL or --url is required.");
            }
            if (!url.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException("FLOW_GEN_URL must be a PostgreSQL JDBC URL.");
            }
            if (user == null || user.isBlank()) {
                throw new IllegalArgumentException("FLOW_GEN_USER or --user is required.");
            }
            if (password == null) {
                throw new IllegalArgumentException("FLOW_GEN_PASSWORD or --password is required; an empty value is allowed.");
            }
            if (schema == null) {
                schema = "public";
            } else if (schema.isBlank()) {
                throw new IllegalArgumentException("FLOW_GEN_SCHEMA or --schema must not be blank.");
            }
            return new Options(url, user, password, schema);
        }

        private static String value(Map<String, String> arguments, Map<String, String> environment,
                                    String option, String variable) {
            return arguments.containsKey(option) ? arguments.get(option) : environment.get(variable);
        }
    }
}
