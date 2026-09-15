package org.cses.flow.controller.flow;

import org.paas.response.wrapper.annotation.SkipResponseWrap;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Inject;
import org.cses.flow.controller.flow.FlowModels.DefinitionView;
import org.cses.flow.controller.flow.FlowModels.DraftView;
import org.cses.flow.controller.flow.FlowModels.FlowView;
import org.cses.flow.controller.flow.FlowModels.InputTypeView;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.pass.authx.security.session.AuthxSession;
import org.paas.session.bind.UserSession;

import java.util.List;

/**
 * User-facing HTTP adapter for the authenticated Flow management surface.
 */
@SkipResponseWrap
@Controller("/api")
public class FlowController {

    private final FlowService flowService;

    @Inject
    public FlowController(FlowService flowService) {
        this.flowService = flowService;
    }

    /**
     * 返回 Flow 支持的输入类型目录。
     * @param session 认证绑定的非空会话
     * @return 内置输入类型目录
     * @throws RuntimeException 请求处理失败时抛出
     */
    @Get("/data-types")
    public List<InputTypeView> dataTypes(
            @UserSession AuthxSession session
    ) {
        return InputTypeView.catalog();
    }

    /**
     * 查询当前公司的草稿列表。
     * @param session 认证绑定且已选择公司的会话
     * @return 当前公司可见的草稿列表
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Get("/flows")
    public List<DraftView> drafts(
            @UserSession AuthxSession session
    ) {
        session.requireActiveCompanyId();
        return flowService.drafts(session).stream()
                .map(draft -> draftView(session, draft))
                .toList();
    }

    /**
     * 保存当前公司的草稿或发布定义。
     * @param session 认证绑定且已选择公司的会话
     * @param command 非空的 Flow 保存命令
     * @return 包含保存结果的响应
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Post("/flows")
    public HttpResponse<DraftView> saveDraft(
            @UserSession AuthxSession session,
            @Body PublishFlowCommand command
    ) {
        session.requireActiveCompanyId();
        Flow draft = flowService.save(
                session,
                requireCommand(command)
        );
        return HttpResponse.ok(draftView(session, draft));
    }

    /**
     * 解析定义并返回预览，不保存数据。
     * @param session 认证绑定的非空会话
     * @param command 包含非空定义文本的命令
     * @return 解析后的定义预览
     * @throws RuntimeException 请求处理失败时抛出
     */
    @Post("/flows/preview")
    public DefinitionView preview(
            @UserSession AuthxSession session,
            @Body PublishFlowCommand command
    ) {
        return new DefinitionView(
                YamlParser.parse(requireCommand(command).source())
        );
    }

    /**
     * 查询当前公司的指定草稿。
     * @param session 认证绑定且已选择公司的会话
     * @param flowKey 非空的 Flow 业务键
     * @return 草稿及其已发布版本信息
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Get("/flows/{flowKey}")
    public DraftView draft(
            @UserSession AuthxSession session,
            String flowKey
    ) {
        session.requireActiveCompanyId();
        return draftView(session, requireDraft(session, flowKey));
    }

    /**
     * 按路径中的业务键保存定义。
     * @param session 认证绑定且已选择公司的会话
     * @param flowKey 非空的 Flow 业务键
     * @param command 非空的定义保存命令
     * @return 保存后的草稿视图
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Put("/flows/{flowKey}")
    public DraftView saveDraft(
            @UserSession AuthxSession session,
            String flowKey,
            @Body PublishFlowCommand command
    ) {
        session.requireActiveCompanyId();
        PublishFlowCommand checked = requireCommand(command);
        Flow draft = flowService.save(
                session,
                PublishFlowCommand.from(
                        flowKey,
                        checked.source(),
                        checked.draft()
                )
        );
        return draftView(session, draft);
    }

    /**
     * 发布当前公司的指定草稿。
     * @param session 认证绑定且已选择公司的会话
     * @param flowKey 非空的 Flow 业务键
     * @return 草稿与发布结果
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Post("/flows/{flowKey}/deploy")
    public DraftView deploy(
            @UserSession AuthxSession session,
            String flowKey
    ) {
        session.requireActiveCompanyId();
        Flow deployed = flowService.save(
                session,
                PublishFlowCommand.from(flowKey, false)
        );
        return DraftView.from(
                requireDraft(session, flowKey),
                deployed
        );
    }

    /**
     * 读取当前公司指定业务版本的 Flow。
     * @param session 认证绑定且已选择公司的会话
     * @param flowKey 非空的 Flow 业务键
     * @param reversion 要查询的正整数业务版本
     * @return 精确版本的 Flow 视图
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Get("/flows/{flowKey}/reversions/{reversion}")
    public FlowView flowReversion(
            @UserSession AuthxSession session,
            String flowKey,
            long reversion
    ) {
        session.requireActiveCompanyId();
        Flow flow = flowService.flow(
                session,
                flowKey,
                reversion
        ).orElseThrow(() -> notFound(
                "Flow reversion does not exist: "
                        + flowKey + "@" + reversion
        ));
        return FlowView.from(flow);
    }

    /**
     * 读取当前公司指定版本的原始定义。
     * @param session 认证绑定且已选择公司的会话
     * @param flowKey 非空的 Flow 业务键
     * @param reversion 要查询的正整数业务版本
     * @return 解析后的版本定义
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Get("/flows/{flowKey}/reversions/{reversion}/definition")
    public DefinitionView flowDefinition(
            @UserSession AuthxSession session,
            String flowKey,
            long reversion
    ) {
        session.requireActiveCompanyId();
        Flow flow = flowService.flow(
                session,
                flowKey,
                reversion
        ).orElseThrow(() -> notFound(
                "Flow reversion does not exist: "
                        + flowKey + "@" + reversion
        ));
        return new DefinitionView(
                YamlParser.parse(flow.source())
        );
    }

    /**
     * 删除当前公司指定类型的 Flow。
     * @param session 认证绑定且已选择公司的会话
     * @param flowKey 非空的 Flow 业务键
     * @param draft true 删除草稿，false 删除正式定义
     * @return 无响应体的删除成功响应
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Delete("/flows/{flowKey}")
    public HttpResponse<?> deleteFlow(
            @UserSession AuthxSession session,
            String flowKey,
            @QueryValue(defaultValue = "true") Boolean draft
    ) {
        session.requireActiveCompanyId();
        flowService.delete(session, flowKey, draft);
        return HttpResponse.noContent();
    }

    private DraftView draftView(
            AuthxSession session,
            Flow draft
    ) {
        Flow deployed = flowService.latestFlow(
                session,
                draft.key()
        ).orElse(null);
        return DraftView.from(draft, deployed);
    }

    private Flow requireDraft(
            AuthxSession session,
            String flowKey
    ) {
        return flowService.draft(session, flowKey)
                .orElseThrow(() -> notFound(
                        "Flow draft does not exist: " + flowKey
                ));
    }

    private static PublishFlowCommand requireCommand(
            PublishFlowCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "Command body must not be empty"
            );
        }
        return command;
    }

    private static HttpStatusException notFound(String message) {
        return new HttpStatusException(HttpStatus.NOT_FOUND, message);
    }
}
