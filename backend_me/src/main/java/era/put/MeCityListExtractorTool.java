package era.put;

import ch.qos.logback.classic.Level;
import era.put.base.Configuration;
import era.put.base.ConfigurationColombia;
import era.put.base.SeleniumUtil;
import era.put.base.Util;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.slf4j.LoggerFactory;

/**
This is a tool to generate the list of regions to put on the code-cached Configuration classes.
It uses the page UI to select the drop down menu of regions, and get the list of cities for
each region.
*/
public class MeCityListExtractorTool {
    private static final Logger logger = LogManager.getLogger(MeCityListExtractorTool.class);

    private static String normalize(String input) {
        Map<String, String> accentsToFlat = new HashMap<>();
        accentsToFlat.put("á", "a");
        accentsToFlat.put("é", "e");
        accentsToFlat.put("í", "i");
        accentsToFlat.put("ó", "o");
        accentsToFlat.put("ú", "u");
        accentsToFlat.put("Á", "a");
        accentsToFlat.put("É", "e");
        accentsToFlat.put("Í", "i");
        accentsToFlat.put("Ó", "o");
        accentsToFlat.put("Ú", "u");
        accentsToFlat.put("ñ", "n");
        accentsToFlat.put("Ñ", "n");
        accentsToFlat.put(" ", "-");
        String output = input;
        for (String key: accentsToFlat.keySet()) {
            output = output.replace(key, accentsToFlat.get(key));
        }
        return output.toLowerCase();
    }

    private static void traverseCitiesList(WebDriver driver, String regionName) {
        WebElement citySelectionDropdownButton = driver.findElement(By.id("select2-prompt_city-container"));
        if (citySelectionDropdownButton == null) {
            logger.error("Can not get cities for region " + regionName);
            return;
        }
        citySelectionDropdownButton.click();
        SeleniumUtil.delay(500);
        WebElement ulCitiesList = driver.findElement(By.id("select2-prompt_city-results"));
        if (citySelectionDropdownButton == null) {
            logger.error("Can not get cities list for region " + regionName);
            return;
        }
        List<WebElement> liCities = ulCitiesList.findElements(By.tagName("li"));
        for (int i = 1; i < liCities.size(); i++) {
            WebElement city = liCities.get(i);
            String cityName = normalize(city.getText());
            String msg = String.format("        \"%s/%s\",", regionName, cityName);
            System.out.println(msg);
        }
    }

    private static void mainFlow() {
        Configuration c = new ConfigurationColombia();
        WebDriver webDriver = SeleniumUtil.initWebDriver(c);

        if (!(webDriver instanceof ChromeDriver)) {
            logger.error("DevTools only available on Chrome/Chromium");
        }

        if (webDriver == null) {
            Util.exitProgram("Can not create connection with browser");
        }

        SeleniumUtil.login(webDriver, c);

        WebElement dropDownRegionsMenuButton = webDriver.findElement(By.className("select2-selection--single"));
        if (dropDownRegionsMenuButton == null) {
            Util.exitProgram("Can not import regions from UI, no dropdown button.");
        }
        dropDownRegionsMenuButton.click();
        SeleniumUtil.delay(2000);
        WebElement ulRegionsList = webDriver.findElement(By.id("select2-prompt_state-results"));
        if (ulRegionsList == null) {
            Util.exitProgram("Can not import regions from UI, no regions list.");
        }
        List<WebElement> liRegions = ulRegionsList.findElements(By.tagName("li"));
        logger.info("Importing " + liRegions.size() + " regions:");
        int n = liRegions.size();
        System.out.println("= Update the configurations region with this code block: =======");
        for (int i = 1; i < n; i++) {
            WebElement li = liRegions.get(i);
            String regionName = normalize(li.getText());
            li.click();
            SeleniumUtil.delay(500);
            traverseCitiesList(webDriver, regionName);
            dropDownRegionsMenuButton.click();
            System.out.println("        \"" + regionName + "\",");
            SeleniumUtil.delay(500);
            liRegions = ulRegionsList.findElements(By.tagName("li"));
            SeleniumUtil.delay(500);
        }
        System.out.println("================================================================");
        SeleniumUtil.closeWebDriver(webDriver);
    }

    public static void main(String args[]) {
        try {
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.OFF);

            mainFlow();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
