package era.put.building;

import com.mongodb.client.MongoCollection;
import era.put.base.Util;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.Properties;
import org.bson.Document;
import org.bson.types.ObjectId;

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
        try {
            BufferedInputStream inputStream = new BufferedInputStream(new URL(url).openStream());
            FileOutputStream fileOS = new FileOutputStream(absolutePath);
            byte data[] = new byte[1024];
            int byteContent;
            while ((byteContent = inputStream.read(data, 0, 1024)) != -1) {
                fileOS.write(data, 0, byteContent);
            }
            return true;
        } catch (IOException e) {
            out.println("ERROR: Can not download image");
            return false;
        }
    }

    public static String imageFilename(Document imageObject, PrintStream out) {
        String _id = ((ObjectId) imageObject.get("_id")).toString();
        int l = _id.length();
        String subfolder = _id.substring(l - 2, l);

        // Check folders
        File root = new File(ME_IMAGE_DOWNLOAD_PATH);
        if (root == null || !root.isDirectory()) {
            out.println("ERROR: Can not write to " + ME_IMAGE_DOWNLOAD_PATH);
            System.exit(121);
        }

        // Do subfolder
        File d = new File(ME_IMAGE_DOWNLOAD_PATH + "/" + subfolder);
        if (d != null && d.exists() && !d.isDirectory()) {
            out.println("ERROR: Can not write to " + ME_IMAGE_DOWNLOAD_PATH + "/" + subfolder);
            System.exit(122);
        }
        if (d == null || !d.exists()) {
            d.mkdir();
            d = new File(ME_IMAGE_DOWNLOAD_PATH + "/" + subfolder);
            if (d != null && !d.isDirectory()) {
                out.println("ERROR: Can not verify write to " + ME_IMAGE_DOWNLOAD_PATH + "/" + subfolder);
                System.exit(123);
            }
        }

        return d.getAbsolutePath() + "/" + _id + ".jpg";
    }

    public static boolean downloadImageIfNeeded(Document imageObject, MongoCollection<Document> image, PrintStream out) {
        if (!(imageObject.get("d") == null || imageObject.getBoolean("d") == true)) {
            return false;
        }
        String url = imageObject.getString("url");
        String imageFile = imageFilename(imageObject, out);

        File fd = new File(imageFile);
        if (fd.exists() && fd.isFile()) {
            return false;
        }

        boolean status = downloadImageFromNet(imageFile, url, out);
        Document o = new Document().append("_id", imageObject.get("_id"));
        Document newDocument = new Document().append("d", status);
        Document query = new Document().append("$set", newDocument);
        image.updateOne(o, query);
        return true;
    }
}
