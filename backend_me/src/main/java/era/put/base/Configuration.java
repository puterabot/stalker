package era.put.base;

public interface Configuration {
    public String[] getServices();
    public String[] getRegions();
    public String getTimeZone();
    public String getRootSiteUrl();
    public boolean useHeadlessBrowser();
}
