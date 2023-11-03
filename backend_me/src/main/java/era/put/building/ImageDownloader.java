package era.put.building;

import com.mongodb.client.MongoCollection;
import era.put.base.Util;
import era.put.datafixing.ColorLogic;
import era.put.datafixing.ImageEmptyBorderRemover;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import vsdk.toolkit.media.RGBPixel;

public class ImageDownloader {
    private static String ME_IMAGE_DOWNLOAD_PATH;

    static {
        try {
            ClassLoader classLoader = Util.class.getClassLoader();
            InputStream input = classLoader.getResourceAsStream("application.properties");
            if (input == null) {
                throw new Exception("application.properties not found on classpath");
            }
            Properties properties = new Properties();
            properties.load(input);
            ME_IMAGE_DOWNLOAD_PATH = properties.getProperty("me.image.download.path");
        } catch (Exception e) {
            ME_IMAGE_DOWNLOAD_PATH = "/tmp";
        }
    }

    private static boolean downloadImageFromNet(String absolutePath, String url, PrintStream out) {
        String subUrl = url.replace("https://static1.mileroticos.com/photos/d/", "");
        out.println("    . " + subUrl + " -> " + absolutePath);
        out.flush();
        try {
            BufferedInputStream inputStream = new BufferedInputStream(new URL(url).openStream());
            FileOutputStream fileOS = new FileOutputStream(absolutePath);
            byte[] data = new byte[1024];
            int byteContent;
            while ((byteContent = inputStream.read(data, 0, 1024)) != -1) {
                fileOS.write(data, 0, byteContent);
            }
            return true;
        } catch (FileNotFoundException e) {
            out.println("ERROR: Can not open url [" + url + "] to download image");
            return false;
        } catch (IOException e) {
            out.println("ERROR: Can not write on to downloaded image file");
            return false;
        }
    }

    public static String imageFilename(String _id, PrintStream out) {
        int l = _id.length();
        String subfolder = _id.substring(l - 2, l);

        // Check folders
        File root = new File(ME_IMAGE_DOWNLOAD_PATH);
        if (!root.isDirectory()) {
            out.println("Can not open directory [" + ME_IMAGE_DOWNLOAD_PATH + "]");
            Util.exitProgram("ERROR: Can not write to " + ME_IMAGE_DOWNLOAD_PATH);
        }

        // Do subfolder
        File directory = new File(ME_IMAGE_DOWNLOAD_PATH + "/" + subfolder);
        if (directory.exists() && !directory.isDirectory()) {
            out.println("Not a directory [" + ME_IMAGE_DOWNLOAD_PATH + "]");
            Util.exitProgram("ERROR: Can not write to " + ME_IMAGE_DOWNLOAD_PATH + "/" + subfolder);
        }
        if (!directory.exists()) {
            if (!directory.mkdir() ) {
                out.println("Can not create directory [" + ME_IMAGE_DOWNLOAD_PATH + "]");
                Util.exitProgram("Can not create directory [" + directory.getAbsolutePath() + "]");
            }
            directory = new File(ME_IMAGE_DOWNLOAD_PATH + "/" + subfolder);
            if (!directory.isDirectory()) {
                out.println("Not a directory [" + ME_IMAGE_DOWNLOAD_PATH + "]");
                Util.exitProgram("ERROR: Can not verify write to " + ME_IMAGE_DOWNLOAD_PATH + "/" + subfolder);
            }
        }

        return directory.getAbsolutePath() + "/" + _id + ".jpg";
    }

    public static boolean downloadImageIfNeeded(Document imageObject, MongoCollection<Document> image, PrintStream out) {
        if (!(imageObject.get("d") == null || imageObject.getBoolean("d") == true)) {
            return false;
        }
        String url = imageObject.getString("url");
        String _id = imageObject.get("_id").toString();
        String imageFile = imageFilename(_id, out);

        File fd = new File(imageFile);
        boolean status;
        if (fd.exists() && fd.isFile()) {
            status = true;
        } else {
            status = downloadImageFromNet(imageFile, url, out);

            if (status) {
                RGBPixel black = new RGBPixel();
                black.r = black.g = black.b = 0;
                AtomicInteger totalImagesProcessed = new AtomicInteger(0);
                AtomicInteger bordersRemoved = new AtomicInteger(0);
                ImageEmptyBorderRemover.removeEmptyImageBorderOnImageFile(imageObject, totalImagesProcessed, bordersRemoved, ColorLogic.BLACK_LOGIC, black);
            }
        }

        Document filter = new Document().append("_id", imageObject.get("_id"));
        Document newDocument = new Document().append("d", status);
        Document query = new Document().append("$set", newDocument);
        image.updateOne(filter, query);
        return true;
    }
}
