package app.quizgate.tv;

/**
 * 构建期配置，由 {@code build.sh} 用环境变量 {@code QUIZGATE_UPGRADE_BASE} 覆盖。
 *
 * <p>为什么做成 Java 常量而不是资源或 remote config：这是全项目**唯一**的出网点，
 * 必须一眼能在反编译结果里看到「它到底连哪里」。做成资源反而更难核对。
 */
public final class BuildCfg {
    private BuildCfg() {}

    /**
     * 升级源根地址，必须以 <b>/</b> 结尾。
     * <p>默认值是生产目录 —— 发布包不配这个变量也能收到后续更新。
     * <p>空串 = 完全关闭在线检查（纯离线行为，一个包都不发）。
     * <p>{@code build.sh} 会用 {@code QUIZGATE_UPGRADE_BASE} 覆盖这里；两处的默认值要一致，
     * 否则「用脚本构建」和「不用脚本构建」的行为会不一样。
     */
    public static final String UPGRADE_BASE = "https://shitang.mianmiangou.cn/static/quizgate-tv/";
}
