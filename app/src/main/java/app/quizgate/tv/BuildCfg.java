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
    public static final String UPGRADE_BASE = "https://7789.cn/y/quizgate-tv/";

    /**
     * 过渡期兜底目录：只有主地址「连不上 / 证书不过 / HTTP 非 200」时才试。
     *
     * <p>为什么要有：1.2.9 把主地址换成了 {@code 7789.cn}，但 2026-11-11 实测该域名
     * 的 443 端口出示的是 {@code CN=*.mianmiangou.cn} 的 Let's Encrypt 证书，
     * 主机名校验必然失败（{@code curl: no alternative certificate subject name matches
     * target host name '7789.cn'}）。证书没补齐之前，没有这条兜底就等于「所有人都收不到更新」，
     * 而这种失效是静默的（检查失败按设计不弹任何提示）。
     *
     * <p>证书补齐、并确认主地址可用之后，把这里置空串即可恢复「只连一个地址」。
     * 空串 = 不启用兜底。{@code build.sh} 在设置 {@code QUIZGATE_UPGRADE_BASE}
     * 时会一并把它清空，免得调试包偷偷连生产。
     */
    public static final String UPGRADE_FALLBACK = "https://shitang.mianmiangou.cn/static/quizgate-tv/";
}
