package co.cask.cdap.guides.workflow;

import co.cask.cdap.api.ProgramLifecycle;
import co.cask.cdap.api.data.stream.StreamBatchReadable;
import co.cask.cdap.api.mapreduce.AbstractMapReduce;
import co.cask.cdap.api.mapreduce.MapReduceContext;
import co.cask.cdap.api.common.Bytes;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MapReduce program for reading the raw purchase events from the stream,
 * parsing them and storing them in the dataset.
 */
public class PurchaseEventParser extends AbstractMapReduce {

  @Override
  public void configure() {
    setName("PurchaseEventParser");
    setDescription("MapReduce program for parsing the purchase events and storing them in the dataset.");
    setOutputDataset("purchaseRecords");
  }

  @Override
  public void beforeSubmit(MapReduceContext context) throws Exception {
    Job job = context.getHadoopJob();
    job.setMapperClass(PurchaseEventParserMapper.class);
    job.setReducerClass(PurchaseEventParserReducer.class);

    job.setMapOutputKeyClass(Text.class);
    job.setOutputValueClass(Purchase.class);

    job.setNumReduceTasks(1);

    // Read the purchase events from the last 60 minutes as input to the mapper.
    final long endTime = context.getLogicalStartTime();
    final long startTime = endTime - TimeUnit.MINUTES.toMillis(60);
    StreamBatchReadable.useStreamInput(context, "purchaseEvents", startTime, endTime);
  }

  // Mapper class to parse the raw purchase events and emit customer and corresponding purchase objects.
  public static class PurchaseEventParserMapper extends Mapper<LongWritable, Text, Text, Text>  {

    @Override
    public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
      // The body of the stream event in contained in the Text value
      String logEvent = value.toString();
      if (!logEvent.isEmpty()) {
        Purchase purchase = Purchase.parse(logEvent);
        if (purchase != null) {
          context.write(new Text(purchase.getCustomer()), new Text(new Gson().toJson(purchase)));
        }
      }
    }
  }

  // Reducer class to aggregate and store the customer purchases into the datasets.
  public static class PurchaseEventParserReducer extends Reducer<Text, Text, byte[], byte[]>
    implements ProgramLifecycle<MapReduceContext> {

    @Override
    public void reduce(Text key, Iterable<Text> values, Context context) throws IOException,
      InterruptedException {
      List<Purchase> purchases = Lists.newArrayList();
      for (Text val : values) {
        purchases.add(new Gson().fromJson(val.toString(), Purchase.class));
      }
      context.write(Bytes.toBytes(key.toString()),
                    Bytes.toBytes(new Gson().toJson(purchases, Purchase.LIST_PURCHASE_TYPE)));
    }

    @Override
    public void initialize(MapReduceContext mapReduceContext) throws Exception {
      //no-op
    }

    @Override
    public void destroy() {
      //no-op
    }
  }
}
