import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * @author kq
 * @date 2021-07-01 17:27
 * @since 2020-0630
 */
public class ConsumeQueueReader {

    public static void main(String[] args) throws IOException {

        String fileName = "00000000000000000000";
        if(args!=null && args.length>0) {
            fileName = args[0];
        }

        decodeCQ(new File(fileName));
    }

    static void decodeCQ(File consumeQueue) throws IOException {
        FileInputStream fis = new FileInputStream(consumeQueue);
        DataInputStream dis = new DataInputStream(fis);

        long preTag = 0;
        long count = 1;
        int index = 0;
        while (true) {
            if(index>1000000){
                break;
            }
            long offset = dis.readLong();
            int size = dis.readInt();
            long tag = dis.readLong();

            if (size == 0) {
                break;
            }
            preTag = tag;
            System.out.printf(" %d %d %d\n", offset,size,tag );
            index++;
        }
        fis.close();
    }

}
