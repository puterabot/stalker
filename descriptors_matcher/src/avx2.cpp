#include <cstdio>
#include <cstdlib>
#include <immintrin.h>

class ImageDescriptor {
  public:
    char id[25];
    unsigned char descriptor[32];

    int
    char2hexadigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        } else if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        } else {
            return -1;
        }
    }
    
    int
    identifyGroup() {
	int highNibble = char2hexadigit(id[22]);
	int lowNibble = char2hexadigit(id[23]);

        if (highNibble < 0 || lowNibble < 0) {
	    return -1;
	}
	
	return highNibble * 16 + lowNibble;;
    }
};

inline int
countDifferentBitsAVX2(const unsigned char array1[32], const unsigned char array2[32]) {
    const __m256i* v1 = reinterpret_cast<const __m256i*>(array1);
    const __m256i* v2 = reinterpret_cast<const __m256i*>(array2);
    __m256i vec1 = _mm256_loadu_si256(v1);
    __m256i vec2 = _mm256_loadu_si256(v2);
    __m256i sumResult = _mm256_sad_epu8(vec1, vec2);
    return  _mm256_extract_epi32(sumResult, 0) + _mm256_extract_epi32(sumResult, 4);
}

void
searchMatchesForPivot(long n, const ImageDescriptor *dataSource, long pivotIndex) {
    int count = 0;
    for (long i = pivotIndex + 1; i < n; i++) {
        int result = countDifferentBitsAVX2(dataSource[pivotIndex].descriptor, dataSource[i].descriptor);
        if (result == 0) {
	    printf("%c%c/%s.jpg ", dataSource[i].id[22], dataSource[i].id[23], dataSource[i].id);
            count++;
        }
    }
    if (count > 0) {
        printf("%c%c/%s.jpg\n",
            dataSource[pivotIndex].id[22], dataSource[pivotIndex].id[23], dataSource[pivotIndex].id);
    }
}

int
main(int argc, char *argv[]) {
    //---------------------------------------------------------------------------
    if (argc != 3) {
	fprintf(stderr, "Usage:\n\t%s <numberOfThreads> <referenceGroup>\n", argv[0]);
	return 1;
    }
    int numberOfThreads = atoi(argv[1]);
    int referenceGroup = atoi(argv[2]);

    //---------------------------------------------------------------------------
    char filename[1024] = "/tmp/data.raw";
    FILE *fd = fopen(filename, "rb");

    if (!fd) {
	fprintf(stderr, "Can not open %s\n", filename);
	return 1;
    }

    fseek(fd, 0, SEEK_END);
    long size = ftell(fd);
    long n = (size / (32 + 24));
    fseek(fd, 0, SEEK_SET);

    ImageDescriptor *data = new ImageDescriptor[n + 1];
    for (long i = 0; i < n; i++) {
        fread(&data[i].id, 25, 1, fd);
        data[i].id[24] = '\0';
        fread(&data[i].descriptor, 32,  1, fd);
    }
    fclose(fd);

    //---------------------------------------------------------------------------
    long p = 0;
    for (long i = 0; i < n - 1; i++) {
	int group = data[i].identifyGroup();
	if (group < 0) {
	    continue;
	}
	int module = group % numberOfThreads;
        if (module != referenceGroup) {
	    continue;
	}
	p++;
        searchMatchesForPivot(n, data, i);
    }
    printf("Processed elements: %ld\n", p);

    //---------------------------------------------------------------------------
    delete data;

    return 0;
}
