package net.i2p.router.web;

/**
 *  @since 0.9.33 moved from HomeHelper
 */
public class App {
    public final String name;
    public final String desc;
    public final String url;
    public final String icon;

    public App(String name, String desc, String url, String icon) {
        this.name = name;
        this.desc = desc;
        this.url = url;
        this.icon = icon;
    }
}

