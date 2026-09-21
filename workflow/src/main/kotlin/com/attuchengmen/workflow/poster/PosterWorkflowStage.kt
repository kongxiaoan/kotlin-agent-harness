package com.attuchengmen.workflow.poster

/**
 * AI 海报生成 Workflow 当前所处的业务阶段。
 *
 * Workflow 的 State 描述业务生命周期进行到了哪里，而不是函数或 Agent 的执行进度。
 */
enum class PosterWorkflowStage {
    /** 等待用户提交海报生成需求。 */
    WAITING_REQUEST,

    /** 系统正在分析用户需求。 */
    ANALYZING_REQUEST,

    /** 系统已经理解用户需求，等待用户确认理解是否正确。 */
    WAITING_REQUEST_CONFIRMATION,

    /** 用户已经确认需求，等待用户选择视觉风格。 */
    WAITING_STYLE_SELECTION,

    /** 系统正在生成海报。 */
    GENERATING,

    /** 海报已经生成，等待用户 Review。 */
    WAITING_REVIEW,

    /** 系统正在根据用户的修改意见重新生成海报。 */
    REVISING,

    /** 用户确认结果满意，Workflow 正常结束。 */
    COMPLETED,

    /** Workflow 因不可恢复错误而终止。 */
    FAILED,
}
