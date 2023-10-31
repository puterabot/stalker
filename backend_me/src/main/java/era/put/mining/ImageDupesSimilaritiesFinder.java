package era.put.mining;

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Projections;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.Binary;

public class ImageDupesSimilaritiesFinder {
    private static final Logger logger = LogManager.getLogger(ImageDupesSimilaritiesFinder.class);

    /**
    @param minDistance should be between 0 and 255.
    */
    public static void performMatchSearch(int minDistance) {
        MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
        if (mongoConnection == null) {
            return;
        }

        ArrayList<Document> conditionsArray = new ArrayList<>();
        conditionsArray.add(new Document("x", true));
        conditionsArray.add(new Document("d", true));
        conditionsArray.add(new Document("af", new BasicDBObject("$exists", true)));
        Document filter = new Document("$and", conditionsArray);
        FindIterable<Document> imageIterable = mongoConnection.image.find(filter).projection(Projections.include("_id", "af.d"));

        try {
            BufferedOutputStream writer = new BufferedOutputStream(new FileOutputStream("./data.raw"));

            imageIterable.forEach((Consumer<? super Document>) imageDocument -> {
                Object descriptorObject = imageDocument.get("af");
                if (!(descriptorObject instanceof Document)) {
                    return;
                }
                Document descriptor = (Document) descriptorObject;
                Object dataObject = descriptor.get("d");
                if (!(dataObject instanceof Binary)) {
                    return;
                }
                Binary data = (Binary) dataObject;
                byte[] array32 = data.getData();
                if (array32.length != 32) {
                    return;
                }
                try {
                    byte[] zeroByte = {0x00};
                    writer.write(imageDocument.get("_id").toString().getBytes(StandardCharsets.UTF_8));
                    writer.write(zeroByte);
                    writer.write(array32);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            writer.flush();
            writer.close();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
