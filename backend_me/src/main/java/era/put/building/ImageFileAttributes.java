package era.put.building;

import lombok.Getter;
import lombok.Setter;
import lombok.Builder;
import lombok.ToString;
import org.bson.Document;

@Getter
@Setter
@Builder
@ToString
public class ImageFileAttributes implements Comparable<ImageFileAttributes> {
    private long size;
    private String shasum;
    private int dx;
    private int dy;

    public static ImageFileAttributes fromDocument(Document source) {
        return ImageFileAttributes.builder()
            .shasum(source.getString("shasum"))
            .size(source.getLong("size"))
            .dy(source.getInteger("dx"))
            .dy(source.getInteger("dy"))
            .build();
    }

    @Override
    public int compareTo(ImageFileAttributes other) {
        int status = this.shasum.compareTo(other.shasum);
        if (status != 0) {
            return status;
        }

        if (this.size > other.size) {
            return 1;
        } else if (this.size < other.size) {
            return -1;
        }

        if (this.dx > other.dx) {
            return 1;
        } else if (this.dx < other.dx) {
            return -1;
        }

        if (this.dy > other.dy) {
            return 1;
        } else if (this.dy < other.dy) {
            return -1;
        }

        return 0;
    }
}
