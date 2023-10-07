package era.put.base;

import com.mongodb.BasicDBObject;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import era.put.building.ImageFileAttributes;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.types.ObjectId;

import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

public class MongoUtil {
    private static final Logger logger = LogManager.getLogger(MongoUtil.class);

    private static String MONGO_SERVER;
    private static String MONGO_USER;
    private static String MONGO_PASSWORD;
    private static String MONGO_DATABASE;
    private static int MONGO_PORT;

    static {
        try {
            ClassLoader classLoader = Util.class.getClassLoader();
            InputStream input = classLoader.getResourceAsStream("application.properties");
            if (input == null) {
                throw new Exception("application.properties not found on classpath");
            }
            Properties properties = new Properties();
            properties.load(input);
            MONGO_SERVER = properties.getProperty("mongo.server");
            MONGO_PORT = Integer.parseInt(properties.getProperty("mongo.port"));
            MONGO_USER = properties.getProperty("mongo.user");
            MONGO_PASSWORD = properties.getProperty("mongo.password");
            MONGO_DATABASE = properties.getProperty("mongo.database");
        } catch (Exception e) {
            MONGO_SERVER = "127.0.0.1";
            MONGO_PORT = 27017;
            MONGO_USER="root";
            MONGO_PASSWORD="putPasswordOnApplicationProperties";
            MONGO_DATABASE="scraper_me";
        }
    }

    public static MongoConnection connectWithMongoDatabase() {
        MongoConnection mongoConnection = new MongoConnection();
        try {
            CodecRegistry codecRegistry = fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                    fromProviders(PojoCodecProvider.builder().automatic(true).build()));
            ConnectionString cs = new ConnectionString("mongodb://" + MONGO_USER + ":" + MONGO_PASSWORD + "@" + MONGO_SERVER + ":" + MONGO_PORT);
            MongoClientSettings settings = MongoClientSettings.builder()
                .codecRegistry(codecRegistry)
                .applyConnectionString(cs)
                .build();
            MongoClient mongoClient = MongoClients.create(settings);
            MongoDatabase database = mongoClient.getDatabase(MONGO_DATABASE);
            mongoConnection.profile = database.getCollection("profile");
            mongoConnection.profileInfo = database.getCollection("profileInfo");
            mongoConnection.post = database.getCollection("post");
            mongoConnection.image = database.getCollection("image");
        } catch (Exception e) {
            logger.error("ERROR connecting to mongo database");
            return null;
        }
        return mongoConnection;
    }

    public static ImageFileAttributes getImageAttributes(Document i) {
        Object o = i.get("a");
        if (!(o instanceof Document document)) {
            return null;
        }
        return ImageFileAttributes.fromDocument(document);
    }

    public static ObjectId getImageParentProfileId(Document i) {
        Object parentPivotO = i.get("u");
        if (!(parentPivotO instanceof ObjectId)) {
            return null;
        }
        return (ObjectId)parentPivotO;
    }

    public static Document getImageParentProfile(Document i, MongoCollection<Document> profile) {
        ObjectId id = getImageParentProfileId(i);
        if (id == null) {
            return null;
        }
        Document filter = new Document("_id", id);
        return profile.find(filter).first();
    }

    public static Document getImageFromPostId(ObjectId postId, MongoCollection<Document> image) {
        Document i = image.find(new Document("p0", postId)).first();
        if (i == null) {
            // Try from p array
            ArrayList<Document> conditions = new ArrayList<>();
            Document noP0 = new Document("p0", new BasicDBObject("$exists", false));
            conditions.add(noP0);
            Document equalsInsideArray = new Document("$eq", postId);
            Document elemMatch = new Document("$elemMatch", equalsInsideArray);
            Document arraySearch = new Document("p", elemMatch);
            conditions.add(arraySearch);
            Document query = new Document("$and", conditions);
            return image.find(query).first();
        }
        return i;
    }

    public static Date getDateFromMdOrT(Document p) {
        Date firstPostDate;
        firstPostDate = p.getDate("md");
        if (firstPostDate == null) {
            p.getDate("t");
        }
        return firstPostDate;
    }
}
