/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.tools.mapred;

import org.apache.hadoop.tools.util.HadoopCompat;
import org.apache.hadoop.tools.util.RetriableCommand;
import org.apache.hadoop.tools.util.ThrottledInputStream;
import org.apache.hadoop.tools.util.DistCpUtils;
import org.apache.hadoop.tools.DistCpOptions.*;
import org.apache.hadoop.tools.DistCpConstants;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.*;
import java.util.EnumSet;

/**
 * This class extends RetriableCommand to implement the copy of files,
 * with retries on failure.
 */
public class RetriableFileCopyCommand extends RetriableCommand {

  private static final Log LOG = LogFactory.getLog(RetriableFileCopyCommand.class);
  private static final int BUFFER_SIZE = 8 * 1024;

  /**
   * Constructor, taking a description of the action.
   * @param description Verbose description of the copy operation.
   */
  public RetriableFileCopyCommand(String description) {
    super(description);
  }

  /**
   * Implementation of RetriableCommand::doExecute().
   * This is the actual copy-implementation.
   * @param arguments Argument-list to the command.
   * @return Number of bytes copied.
   * @throws Exception: CopyReadException, if there are read-failures. All other
   *         failures are IOExceptions.
   */
  @SuppressWarnings("unchecked")
  @Override
  protected Object doExecute(Object... arguments) throws Exception {
    assert arguments.length == 4 : "Unexpected argument list.";
    FileStatus source = (FileStatus)arguments[0];
    assert !source.isDir() : "Unexpected file-status. Expected file.";
    Path target = (Path)arguments[1];
    Mapper.Context context = (Mapper.Context)arguments[2];
    EnumSet<FileAttribute> fileAttributes
            = (EnumSet<FileAttribute>)arguments[3];
    return doCopy(source, target, context, fileAttributes);
  }

  private long doCopy(FileStatus sourceFileStatus, Path target,
                      Mapper.Context context,
                      EnumSet<FileAttribute> fileAttributes)
          throws IOException {

    Path tmpTargetPath = getTmpFile(target, context);
    final Configuration configuration = HadoopCompat.getTaskConfiguration(context);
    FileSystem targetFS = target.getFileSystem(configuration);

    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Copying " + sourceFileStatus.getPath() + " to " + target);
        LOG.debug("Tmp-file path: " + tmpTargetPath);
      }
      FileSystem sourceFS = sourceFileStatus.getPath().getFileSystem(
              configuration);
      long bytesRead = copyToTmpFile(tmpTargetPath, targetFS, sourceFileStatus,
                                     context, fileAttributes);

      compareFileLengths(sourceFileStatus, tmpTargetPath, configuration, bytesRead);
      if (bytesRead > 0) {
        compareCheckSums(sourceFS, sourceFileStatus.getPath(), targetFS, tmpTargetPath);
      }
      promoteTmpToTarget(tmpTargetPath, target, targetFS);
      return bytesRead;

    } finally {
      if (targetFS.exists(tmpTargetPath))
        targetFS.delete(tmpTargetPath, false);
    }
  }

  private long copyToTmpFile(Path tmpTargetPath, FileSystem targetFS,
                             FileStatus sourceFileStatus, Mapper.Context context,
                             EnumSet<FileAttribute> fileAttributes)
                             throws IOException {
    OutputStream outStream = new BufferedOutputStream(targetFS.create(
            tmpTargetPath, true, BUFFER_SIZE,
            getReplicationFactor(fileAttributes, sourceFileStatus, targetFS),
            getBlockSize(fileAttributes, sourceFileStatus, targetFS), context));
    return copyBytes(sourceFileStatus, outStream, BUFFER_SIZE, true, context);
  }

  private void compareFileLengths(FileStatus sourceFileStatus, Path target,
                                  Configuration configuration, long bytesRead)
                                  throws IOException {
    final Path sourcePath = sourceFileStatus.getPath();
    FileSystem fs = sourcePath.getFileSystem(configuration);
    if (fs.getFileStatus(sourcePath).getLen() != bytesRead)
      throw new IOException("Mismatch in length of source:" + sourcePath
                + " and target:" + target);
  }

  private void compareCheckSums(FileSystem sourceFS, Path source,
                                FileSystem targetFS, Path target)
                                throws IOException {
    if (!DistCpUtils.checksumsAreEqual(sourceFS, source, targetFS, target))
      throw new IOException("Check-sum mismatch between "
                              + source + " and " + target);

  }

  //If target file exists and unable to delete target - fail
  //If target doesn't exist and unable to create parent folder - fail
  //If target is successfully deleted and parent exists, if rename fails - fail
  private void promoteTmpToTarget(Path tmpTarget, Path target, FileSystem fs)
                                  throws IOException {
    if ((fs.exists(target) && !fs.delete(target, false))
        || (!fs.exists(target.getParent()) && !fs.mkdirs(target.getParent()))
        || !fs.rename(tmpTarget, target)) {
      throw new IOException("Failed to promote tmp-file:" + tmpTarget
                              + " to: " + target);
    }
  }

  private Path getTmpFile(Path target, Mapper.Context context) {
    Path targetWorkPath = new Path(HadoopCompat.getTaskConfiguration(context).
        get(DistCpConstants.CONF_LABEL_TARGET_WORK_PATH));

    Path root = target.equals(targetWorkPath)? targetWorkPath.getParent() : targetWorkPath;
    LOG.info("Creating temp file: " +
        new Path(root, ".distcp.tmp." + context.getTaskAttemptID().toString()));
    return new Path(root, ".distcp.tmp." + context.getTaskAttemptID().toString());
  }

  private long copyBytes(FileStatus sourceFileStatus, OutputStream outStream,
                         int bufferSize, boolean mustCloseStream,
                         Mapper.Context context) throws IOException {
    Path source = sourceFileStatus.getPath();
    byte buf[] = new byte[bufferSize];
    ThrottledInputStream inStream = null;
    long totalBytesRead = 0;

    try {
      inStream = getInputStream(source, HadoopCompat.getTaskConfiguration(context));
      int bytesRead = readBytes(inStream, buf);
      while (bytesRead >= 0) {
        totalBytesRead += bytesRead;
        outStream.write(buf, 0, bytesRead);
        updateContextStatus(totalBytesRead, context, sourceFileStatus);
        bytesRead = inStream.read(buf);
      }
      HadoopCompat.incrementCounter(HadoopCompat.getCounter(context,
        CopyMapper.Counter.SLEEP_TIME_MS), inStream.getTotalSleepTime());
      LOG.info("STATS: " + inStream);
    } finally {
      if (mustCloseStream) {
        IOUtils.cleanup(LOG, inStream);
        try {
          outStream.close();
        }
        catch(IOException exception) {
          LOG.error("Could not close output-stream. ", exception);
          throw exception;
        }
      }
    }

    return totalBytesRead;
  }

  private void updateContextStatus(long totalBytesRead, Mapper.Context context,
                                   FileStatus sourceFileStatus) {
    StringBuilder message = new StringBuilder(DistCpUtils.getFormatter()
                .format(totalBytesRead * 100.0f / sourceFileStatus.getLen()));
    message.append("% ")
            .append(description).append(" [")
            .append(DistCpUtils.getStringDescriptionFor(totalBytesRead))
            .append('/')
        .append(DistCpUtils.getStringDescriptionFor(sourceFileStatus.getLen()))
            .append(']');
    HadoopCompat.setStatus(context, message.toString());
  }

  private static int readBytes(InputStream inStream, byte buf[])
          throws IOException {
    try {
      return inStream.read(buf);
    }
    catch (IOException e) {
      throw new CopyReadException(e);
    }
  }

  private static ThrottledInputStream getInputStream(Path path, Configuration conf)
          throws IOException {
    try {
      FileSystem fs = path.getFileSystem(conf);
      long bandwidthKB = getAllowedBandwidth(conf);
      return new ThrottledInputStream(new BufferedInputStream(fs.open(path)),
          bandwidthKB * 1024);
    }
    catch (IOException e) {
      throw new CopyReadException(e);
    }
  }

  private static long getAllowedBandwidth(Configuration conf) {
    return (long) conf.getInt(DistCpConstants.CONF_LABEL_BANDWIDTH_KB,
        DistCpConstants.DEFAULT_BANDWIDTH_KB);
  }

    private static short getReplicationFactor(
          EnumSet<FileAttribute> fileAttributes,
          FileStatus sourceFile, FileSystem targetFS) {
    return fileAttributes.contains(FileAttribute.REPLICATION)?
            sourceFile.getReplication() : targetFS.getDefaultReplication();
  }

  private static long getBlockSize(
          EnumSet<FileAttribute> fileAttributes,
          FileStatus sourceFile, FileSystem targetFS) {
    return fileAttributes.contains(FileAttribute.BLOCKSIZE)?
            sourceFile.getBlockSize() : targetFS.getDefaultBlockSize();
  }

  /**
   * Special subclass of IOException. This is used to distinguish read-operation
   * failures from other kinds of IOExceptions.
   * The failure to read from source is dealt with specially, in the CopyMapper.
   * Such failures may be skipped if the DistCpOptions indicate so.
   * Write failures are intolerable, and amount to CopyMapper failure.  
   */
  public static class CopyReadException extends IOException {
    public CopyReadException(Throwable rootCause) {
      super(rootCause);
    }
  }
}

