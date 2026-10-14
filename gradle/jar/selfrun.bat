@echo off

REM NOTE: Do not use backquotes in this bat file because backquotes are unintentionally recognized by sh.
REM NOTE: Just quotes are available for [ for /f "delims=" %%w ('...') ].

setlocal

REM Do not use %0 to identify the JAR (bat) file.
REM %0 is just "flow" when run by just "> flow" while %0 is "flow.bat" when run by "> flow.bat".
SET this=%~f0

REM Plugins path default to pwd & must be exported as env var
SET "current_dir=%~dp0"
IF NOT DEFINED flow_plugins_path (set "flow_plugins_path=%current_dir%plugins\")

REM Flow configuration env vars

IF NOT DEFINED flow_configuration_path (set "flow_configuration_path=%current_dir%confs\")

IF DEFINED flow_configuration (
    echo %flow_configuration% > "%flow_configuration_path%application.yml"
    set "micronaut_config_files=%flow_configuration_path%application.yml"
)

REM Opens java.nio due to https://github.com/snowflakedb/snowflake-jdbc/issues/589
REM Opens java.util due to https://github.com/Azure/azure-sdk-for-java/issues/27806
REM Opens java.lang due to upstream issue #1755, see https://github.com/micronaut-projects/micronaut-core/issues/9573
SET "JAVA_ADD_OPENS=--add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED"

REM Java options that Flow engineers think are best for Flow, they should be added before JAVA_OPTS so they are overridable:
REM -XX:MaxRAMPercentage=50.0: configure max heap to 50% of available RAM (default 25%)
SET "FLOW_JAVA_OPTS=-XX:MaxRAMPercentage=50.0"

java %FLOW_JAVA_OPTS% %JAVA_OPTS% %JAVA_ADD_OPENS% --enable-native-access=ALL-UNNAMED -jar "%this%" %*

ENDLOCAL

exit /b %ERRORLEVEL%
