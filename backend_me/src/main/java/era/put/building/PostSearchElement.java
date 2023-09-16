package era.put.building;

import java.util.List;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.bson.types.ObjectId;

@Getter
@Setter
@Builder
public class PostSearchElement {
    private List<String> urls;
    private ObjectId profileId;
}
