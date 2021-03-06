package com.nearinfinity.blur.mapreduce.lib;

import java.io.IOException;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.Directory;

import com.nearinfinity.blur.mapreduce.BlurRecord;
import com.nearinfinity.blur.store.hdfs.HdfsDirectory;
import com.nearinfinity.blur.utils.RowDocumentUtil;

public class BlurRecordReader extends RecordReader<Text, BlurRecord> {

  private IndexReader reader;
  private Directory directory;
  private int startingDocId;
  private int endingDocId;
  private int position;
  private Text rowid = new Text();
  private BlurRecord record = new BlurRecord();

  @Override
  public void initialize(InputSplit split, TaskAttemptContext context) throws IOException, InterruptedException {
    BlurInputSplit blurSplit = (BlurInputSplit) split;
    Path path = blurSplit.getIndexPath();
    String segmentName = blurSplit.getSegmentName();
    startingDocId = blurSplit.getStartingDocId();
    endingDocId = blurSplit.getEndingDocId();
    directory = new HdfsDirectory(path);
    
    IndexCommit commit = Utils.findLatest(directory);
    reader = Utils.openSegmentReader(directory, commit, segmentName,Utils.getTermInfosIndexDivisor(context.getConfiguration()));
    int maxDoc = reader.maxDoc();
    if (endingDocId >= maxDoc) {
      endingDocId = maxDoc - 1;
    }
    position = startingDocId - 1;
  }

  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    do {
      position++;
      if (position > endingDocId) {
        return false;
      }
    } while (reader.isDeleted(position));
    readDocument();
    return true;
  }

  private void readDocument() throws CorruptIndexException, IOException {
    Document document = reader.document(position);
    record.reset();
    rowid.set(RowDocumentUtil.readRecord(document, record));
  }

  @Override
  public Text getCurrentKey() throws IOException, InterruptedException {
    return rowid;
  }

  @Override
  public BlurRecord getCurrentValue() throws IOException, InterruptedException {
    return record;
  }

  @Override
  public float getProgress() throws IOException, InterruptedException {
    int total = endingDocId - startingDocId;
    return (float) position / (float) total;
  }

  @Override
  public void close() throws IOException {
    reader.close();
    directory.close();
  }
}
