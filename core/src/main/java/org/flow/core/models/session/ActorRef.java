package org.flow.core.models.session;

/** Session 中提取的操作者，不包含认证凭证。 */
public record ActorRef(String id, String name) {
    /** 从已校验的用户标识和显示名称创建操作者快照。 */
    public static ActorRef from(String id, String name) {
        return new ActorRef(id, name);
    }
}
