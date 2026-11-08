package com.xinsx.bzgk;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 一级页面:「综合」。
 * 汇总当前收录的官方库入口,点击任意一条进入二级「细分」页(WebActivity)。
 */
public class HomeActivity extends android.app.Activity {

    /** 一级「综合」页收录的官方库。 */
    private static final SiteEntry[] SITES = new SiteEntry[]{
            new SiteEntry(
                    "国家标准全文公开系统",
                    "强制性 / 推荐性国家标准、指导性技术文件、国家标准外文版",
                    "https://openstd.samr.gov.cn/bzgk/std/",
                    "openstd.samr.gov.cn",
                    "标"),
            new SiteEntry(
                    "国家行政法规库",
                    "现行有效行政法规全文，可在线阅读、下载文字版与图片版",
                    // 站点根路径只是一层 JS 跳转壳(会自动跳到 search2.html),
                    // 直接指向落地页,避免返回键退到壳页后又被弹回
                    "https://xzfg.moj.gov.cn/search2.html",
                    "xzfg.moj.gov.cn",
                    "法"),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F2F4F7"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(24), pad, dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题
        TextView title = new TextView(this);
        title.setText("综合");
        title.setTextColor(Color.parseColor("#1A1C1E"));
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText("官方标准与法规库统一入口");
        subtitle.setTextColor(Color.parseColor("#6B7280"));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(6), 0, dp(20));
        root.addView(subtitle);

        // 各库入口
        for (SiteEntry entry : SITES) {
            root.addView(buildCard(entry), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                    root.getChildAt(root.getChildCount() - 1).getLayoutParams();
            lp.bottomMargin = dp(14);
            root.getChildAt(root.getChildCount() - 1).setLayoutParams(lp);
        }

        // 底部说明
        TextView footer = new TextView(this);
        footer.setText("数据来源：国家市场监督管理总局 · 司法部");
        footer.setTextColor(Color.parseColor("#9AA0A6"));
        footer.setTextSize(11);
        footer.setPadding(dp(4), dp(8), dp(4), 0);
        root.addView(footer);

        setContentView(scroll);
    }

    /** 单个官方库入口卡片。 */
    private View buildCard(final SiteEntry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundRect(Color.WHITE, dp(14), 0, 0));

        // 左侧色块图标
        TextView badge = new TextView(this);
        badge.setText(entry.badge);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(18);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(roundRect(Color.parseColor("#1B6FD6"), dp(12), 0, 0));
        card.addView(badge, new LinearLayout.LayoutParams(dp(48), dp(48)));

        // 中间文字
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textLp.leftMargin = dp(14);
        card.addView(textBox, textLp);

        TextView name = new TextView(this);
        name.setText(entry.title);
        name.setTextColor(Color.parseColor("#1A1C1E"));
        name.setTextSize(16);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        textBox.addView(name);

        TextView desc = new TextView(this);
        desc.setText(entry.subtitle);
        desc.setTextColor(Color.parseColor("#6B7280"));
        desc.setTextSize(12);
        desc.setPadding(0, dp(6), 0, 0);
        desc.setMaxLines(2);
        textBox.addView(desc);

        // 右侧箭头
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(Color.parseColor("#C3C7CB"));
        arrow.setTextSize(24);
        card.addView(arrow, new LinearLayout.LayoutParams(
                dp(20), ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(v -> {
            Intent i = new Intent(this, WebActivity.class);
            i.putExtra(WebActivity.EXTRA_TITLE, entry.title);
            i.putExtra(WebActivity.EXTRA_URL, entry.url);
            i.putExtra(WebActivity.EXTRA_HOST, entry.host);
            startActivity(i);
        });
        return card;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        if (strokeWidth > 0) {
            gd.setStroke(strokeWidth, strokeColor);
        }
        return gd;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
