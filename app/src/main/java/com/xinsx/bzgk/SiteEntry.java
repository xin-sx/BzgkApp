package com.xinsx.bzgk;

/**
 * 一级「综合」页上的一个官方库条目。
 * 点击后进入二级「细分」页,由 WebActivity 加载 {@link #url}。
 */
public class SiteEntry {

    /** 库名称,如"国家行政法规库"。 */
    public final String title;
    /** 副标题,补充说明收录范围。 */
    public final String subtitle;
    /** 站点首页地址。 */
    public final String url;
    /** 站内允许在 App 内打开的域名(不含协议)。 */
    public final String host;
    /** 入口图标文字,取名称首字。 */
    public final String badge;

    public SiteEntry(String title, String subtitle, String url, String host, String badge) {
        this.title = title;
        this.subtitle = subtitle;
        this.url = url;
        this.host = host;
        this.badge = badge;
    }
}
