package era.put;

import ch.qos.logback.classic.Level;
import com.mongodb.BasicDBObject;
import com.mongodb.client.model.Filters;
import era.put.base.Configuration;
import era.put.base.ConfigurationColombia;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import era.put.base.Util;
import era.put.building.FileToolReport;
import era.put.datafixing.Fixes;
import era.put.building.ImageAnalyser;
import era.put.building.RepeatedImageDetector;
import era.put.interleaving.ImageInterleaver;
import era.put.interleaving.PostInterleaver;
import era.put.interleaving.ProfileInfoInterleaver;
import era.put.mining.ImageInfo;
import java.io.PrintStream;
import java.util.Date;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.slf4j.LoggerFactory;

public class MeLocalDataProcessorApp {
    private static final Logger logger = LogManager.getLogger(MeLocalDataProcessorApp.class);

    private static void processImages(Configuration c) throws Exception {
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        // Update image date if not present
        for (Document i: mongoConnection.image.find(Filters.exists("md", false))) {
            ImageAnalyser.updateDate(mongoConnection.image, i, c);
        }

        // Download images
        Fixes.deleteDanglingImages(mongoConnection.image);
        Fixes.downloadMissingImages(mongoConnection.image);

        // Gather information from image files
        FileToolReport fileToolReport = new FileToolReport();
        Document filter = new Document("a", new BasicDBObject("$exists", false)).append("d", true);
        for (Document i: mongoConnection.image.find(filter)) {
            ImageAnalyser.processImageFile(mongoConnection.image, i, fileToolReport, System.out);
        }
        fileToolReport.print();
        RepeatedImageDetector.groupImages(mongoConnection.image);
    }

    private static void fixDatabaseCollections() throws Exception {
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        Fixes.fixPostCollection(mongoConnection.post);
        Fixes.fixRelationships(mongoConnection);
    }

    private static void mainSequence() throws Exception {
        // 0. Global init
        Date startDate = new Date();
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.OFF);
        Configuration c = new ConfigurationColombia();

        logger.info("Application started, timestamp: {}", startDate);

        // 1. Analise images on disk
        processImages(c);

        // 2. Execute fixes
        fixDatabaseCollections();

        // 3. Print some dataset trivia
        ImageInfo.reportProfilesWithCommonImages();

        // 4. Build extended information
        ImageInterleaver.createP0References(System.out);
        PostInterleaver.linkPostsToProfiles(System.out);
        ProfileInfoInterleaver.createExtendedProfileInfo(new PrintStream("./log/userStats.csv"));

        // Closing application
        Date endDate = new Date();
        logger.info("Application ended, timestamp: {}", endDate);
        Util.reportDeltaTime(startDate, endDate);
    }

    public static void main(String[] args) {
        try {
            mainSequence();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
