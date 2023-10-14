package era.put;

import ch.qos.logback.classic.Level;
import era.put.base.Configuration;
import era.put.base.ConfigurationColombia;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import era.put.base.Util;
import era.put.datafixing.Fixes;
import era.put.datafixing.ImageFixes;
import era.put.interleaving.ImageInterleaver;
import era.put.interleaving.PostInterleaver;
import era.put.interleaving.ProfileInfoInterleaver;
import era.put.mining.ImageInfo;
import java.io.PrintStream;
import java.util.Date;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.LoggerFactory;

public class MeLocalDataProcessorApp {
    private static final Logger logger = LogManager.getLogger(MeLocalDataProcessorApp.class);

    private static void completeImageDatabaseCollection(Configuration c) throws Exception {
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        ImageFixes.updateImageDates(c, mongoConnection);
        ImageFixes.deleteChildImageFiles(mongoConnection.image);
        ImageFixes.downloadMissingImages(mongoConnection.image);
        ImageFixes.buildImageSizeAndShaSumDescriptors(mongoConnection);
    }

    private static void completePostAndProfileDatabaseCollections() throws Exception {
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
        completeImageDatabaseCollection(c);

        // 2. Execute fixes on posts and profiles
        completePostAndProfileDatabaseCollections();

        // 3. Process intra-profile similarity hints by shasum image descriptors
        ImageInfo.deleteExternalChildImages();

        // TODO: Compute / update findimagedupes image descriptors


        // TODO: Compute / update Yolo object detection (including faces and tatoos)

        // TODO: Compute / update face id image descriptors

        // TODO: Add the similarity hints by findimagedupes image descriptors

        // TODO: Add the similarity hints by face id image descriptors

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
