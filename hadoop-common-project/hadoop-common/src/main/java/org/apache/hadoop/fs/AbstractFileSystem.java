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
package org.apache.hadoop.fs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem.Statistics;
import org.apache.hadoop.fs.Options.ChecksumOpt;
import org.apache.hadoop.fs.Options.CreateOpts;
import org.apache.hadoop.fs.Options.Rename;
import org.apache.hadoop.fs.impl.AbstractFSBuilderImpl;
import org.apache.hadoop.fs.impl.OpenFileParameters;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.LambdaUtils;
import org.apache.hadoop.util.Progressable;

import org.apache.hadoop.classification.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.fs.impl.PathCapabilitiesSupport.validatePathCapabilityArgs;

/**
 * This class provides an interface for implementors of a Hadoop file system
 * (analogous to the VFS of Unix). Applications do not access this class;
 * instead they access files across all file systems using {@link FileContext}.
 * 
 * Pathnames passed to AbstractFileSystem can be fully qualified URI that
 * matches the "this" file system (ie same scheme and authority) 
 * or a Slash-relative name that is assumed to be relative
 * to the root of the "this" file system .
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public abstract class AbstractFileSystem implements PathCapabilities {
  static final Logger LOG = LoggerFactory.getLogger(AbstractFileSystem.class);

  /** Recording statistics per a file system class. */
  private static final Map<URI, Statistics> 
      STATISTICS_TABLE = new HashMap<URI, Statistics>();
  
  /** Cache of constructors for each file system class. */
  private static final Map<Class<?>, Constructor<?>> CONSTRUCTOR_CACHE = 
    new ConcurrentHashMap<Class<?>, Constructor<?>>();
  
  private static final Class<?>[] URI_CONFIG_ARGS = 
    new Class[]{URI.class, Configuration.class};
  
  /** The statistics for this file system. */
  protected Statistics statistics;

  @VisibleForTesting
  static final String NO_ABSTRACT_FS_ERROR = "No AbstractFileSystem configured for scheme";
  
  private final URI myUri;
  
  public Statistics getStatistics() {
    return statistics;
  }
  
  /**
   * Returns true if the specified string is considered valid in the path part
   * of a URI by this file system.  The default implementation enforces the rules
   * of HDFS, but subclasses may override this method to implement specific
   * validation rules for specific file systems.
   * 
   * @param src String source filename to check, path part of the URI
   * @return boolean true if the specified string is considered valid
   */
  public boolean isValidName(String src) {
    // Prohibit ".." "." and anything containing ":"
    StringTokenizer tokens = new StringTokenizer(src, Path.SEPARATOR);
    while(tokens.hasMoreTokens()) {
      String element = tokens.nextToken();
      if (element.equals("..") ||
          element.equals(".")  ||
          (element.indexOf(":") >= 0)) {
        return false;
      }
    }
    return true;
  }
  
  /** 
   * Create an object for the given class and initialize it from conf.
   * @param theClass class of which an object is created
   * @param conf Configuration
   * @return a new object
   */
  @SuppressWarnings("unchecked")
  static <T> T newInstance(Class<T> theClass,
    URI uri, Configuration conf) {
    T result;
    try {
      Constructor<T> meth = (Constructor<T>) CONSTRUCTOR_CACHE.get(theClass);
      if (meth == null) {
        meth = theClass.getDeclaredConstructor(URI_CONFIG_ARGS);
        meth.setAccessible(true);
        CONSTRUCTOR_CACHE.put(theClass, meth);
      }
      result = meth.newInstance(uri, conf);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else {
        throw new RuntimeException(cause);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return result;
  }
  
  /**
   * Create a file system instance for the specified uri using the conf. The
   * conf is used to find the class name that implements the file system. The
   * conf is also passed to the file system for its configuration.
   *
   * @param uri URI of the file system
   * @param conf Configuration for the file system
   * 
   * @return Returns the file system for the given URI
   *
   * @throws UnsupportedFileSystemException file system for <code>uri</code> is
   *           not found
   */
  public static AbstractFileSystem createFileSystem(URI uri, Configuration conf)
      throws UnsupportedFileSystemException {
    final String fsImplConf = String.format("fs.AbstractFileSystem.%s.impl",
        uri.getScheme());

    Class<?> clazz = conf.getClass(fsImplConf, null);
    if (clazz == null) {
      throw new UnsupportedFileSystemException(String.format(
          "%s=null: %s: %s",
          fsImplConf, NO_ABSTRACT_FS_ERROR, uri.getScheme()));
    }
    return (AbstractFileSystem) newInstance(clazz, uri, conf);
  }

  /**
   * Get the statistics for a particular file system.
   * 
   * @param uri
   *          used as key to lookup STATISTICS_TABLE. Only scheme and authority
   *          part of the uri are used.
   * @return a statistics object
   */
  protected static synchronized Statistics getStatistics(URI uri) {
    String scheme = uri.getScheme();
    if (scheme == null) {
      throw new IllegalArgumentException("Scheme not defined in the uri: "
          + uri);
    }
    URI baseUri = getBaseUri(uri);
    Statistics result = STATISTICS_TABLE.get(baseUri);
    if (result == null) {
      result = new Statistics(scheme);
      STATISTICS_TABLE.put(baseUri, result);
    }
    return result;
  }
  
  private static URI getBaseUri(URI uri) {
    String scheme = uri.getScheme();
    String authority = uri.getAuthority();
    String baseUriString = scheme + "://";
    if (authority != null) {
      baseUriString = baseUriString + authority;
    } else {
      baseUriString = baseUriString + "/";
    }
    return URI.create(baseUriString);
  }
  
  public static synchronized void clearStatistics() {
    for(Statistics stat: STATISTICS_TABLE.values()) {
      stat.reset();
    }
  }

  /**
   * Prints statistics for all file systems.
   */
  public static synchronized void printStatistics() {
    for (Map.Entry<URI, Statistics> pair : STATISTICS_TABLE.entrySet()) {
      System.out.println("  FileSystem " + pair.getKey().getScheme() + "://"
          + pair.getKey().getAuthority() + ": " + pair.getValue());
    }
  }
  
  protected static synchronized Map<URI, Statistics> getAllStatistics() {
    Map<URI, Statistics> statsMap = new HashMap<URI, Statistics>(
        STATISTICS_TABLE.size());
    for (Map.Entry<URI, Statistics> pair : STATISTICS_TABLE.entrySet()) {
      URI key = pair.getKey();
      Statistics value = pair.getValue();
      Statistics newStatsObj = new Statistics(value);
      statsMap.put(URI.create(key.toString()), newStatsObj);
    }
    return statsMap;
  }

  /**
   * The main factory method for creating a file system. Get a file system for
   * the URI's scheme and authority. The scheme of the <code>uri</code>
   * determines a configuration property name,
   * <code>fs.AbstractFileSystem.<i>scheme</i>.impl</code> whose value names the
   * AbstractFileSystem class.
   * 
   * The entire URI and conf is passed to the AbstractFileSystem factory method.
   * 
   * @param uri for the file system to be created.
   * @param conf which is passed to the file system impl.
   * 
   * @return file system for the given URI.
   * 
   * @throws UnsupportedFileSystemException if the file system for
   *           <code>uri</code> is not supported.
   */
  public static AbstractFileSystem get(final URI uri, final Configuration conf)
      throws UnsupportedFileSystemException {
    return createFileSystem(uri, conf);
  }

  /**
   * Constructor to be called by subclasses.
   * 
   * @param uri for this file system.
   * @param supportedScheme the scheme supported by the implementor
   * @param authorityNeeded if true then theURI must have authority, if false
   *          then the URI must have null authority.
   * @param defaultPort default port to use if port is not specified in the URI.
   * @throws URISyntaxException <code>uri</code> has syntax error
   */
  public AbstractFileSystem(final URI uri, final String supportedScheme,
      final boolean authorityNeeded, final int defaultPort)
      throws URISyntaxException {
    myUri = getUri(uri, supportedScheme, authorityNeeded, defaultPort);
    statistics = getStatistics(uri); 
  }

  /**
   * Check that the Uri's scheme matches.
   *
   * @param uri name URI of the FS.
   * @param supportedScheme supported scheme.
   */
  public void checkScheme(URI uri, String supportedScheme) {
    String scheme = uri.getScheme();
    if (scheme == null) {
      throw new HadoopIllegalArgumentException("Uri without scheme: " + uri);
    }
    if (!scheme.equals(supportedScheme)) {
      throw new HadoopIllegalArgumentException("Uri scheme " + uri
          + " does not match the scheme " + supportedScheme);
    }
  }

  /**
   * Get the URI for the file system based on the given URI. The path, query
   * part of the given URI is stripped out and default file system port is used
   * to form the URI.
   * 
   * @param uri FileSystem URI.
   * @param authorityNeeded if true authority cannot be null in the URI. If
   *          false authority must be null.
   * @param defaultPort default port to use if port is not specified in the URI.
   * 
   * @return URI of the file system
   * 
   * @throws URISyntaxException <code>uri</code> has syntax error
   */
  private URI getUri(URI uri, String supportedScheme,
      boolean authorityNeeded, int defaultPort) throws URISyntaxException {
    checkScheme(uri, supportedScheme);
    // A file system implementation that requires authority must always
    // specify default port
    if (defaultPort < 0 && authorityNeeded) {
      throw new HadoopIllegalArgumentException(
          "FileSystem implementation error -  default port " + defaultPort
              + " is not valid");
    }
    String authority = uri.getAuthority();
    if (authority == null) {
       if (authorityNeeded) {
         throw new HadoopIllegalArgumentException("Uri without authority: " + uri);
       } else {
         return new URI(supportedScheme + ":///");
       }   
    }
    // authority is non null  - AuthorityNeeded may be true or false.
    int port = uri.getPort();
    port = (port == -1 ? defaultPort : port);
    if (port == -1) { // no port supplied and default port is not specified
      return new URI(supportedScheme, authority, "/", null);
    }
    return new URI(supportedScheme + "://" + uri.getHost() + ":" + port);
  }
  
  /**
   * The default port of this file system.
   * 
   * @return default port of this file system's Uri scheme
   *         A uri with a port of -1 =&gt; default port;
   */
  public abstract int getUriDefaultPort();

  /**
   * Returns a URI whose scheme and authority identify this FileSystem.
   * 
   * @return the uri of this file system.
   */
  public URI getUri() {
    return myUri;
  }
  
  /**
   * Check that a Path belongs to this FileSystem.
   * 
   * If the path is fully qualified URI, then its scheme and authority
   * matches that of this file system. Otherwise the path must be 
   * slash-relative name.
   * @param path the path.
   * @throws InvalidPathException if the path is invalid
   */
  public void checkPath(Path path) {
    URI uri = path.toUri();
    String thatScheme = uri.getScheme();
    String thatAuthority = uri.getAuthority();
    if (thatScheme == null) {
      if (thatAuthority == null) {
        if (path.isUriPathAbsolute()) {
          return;
        }
        throw new InvalidPathException("relative paths not allowed:" + 
            path);
      } else {
        throw new InvalidPathException(
            "Path without scheme with non-null authority:" + path);
      }
    }
    String thisScheme = this.getUri().getScheme();
    String thisHost = this.getUri().getHost();
    String thatHost = uri.getHost();
    
    // Schemes and hosts must match.
    // Allow for null Authority for file:///
    if (!thisScheme.equalsIgnoreCase(thatScheme) ||
       (thisHost != null && 
            !thisHost.equalsIgnoreCase(thatHost)) ||
       (thisHost == null && thatHost != null)) {
      throw new InvalidPathException("Wrong FS: " + path + ", expected: "
          + this.getUri());
    }
    
    // Ports must match, unless this FS instance is using the default port, in
    // which case the port may be omitted from the given URI
    int thisPort = this.getUri().getPort();
    int thatPort = uri.getPort();
    if (thatPort == -1) { // -1 => defaultPort of Uri scheme
      thatPort = this.getUriDefaultPort();
    }
    if (thisPort != thatPort) {
      throw new InvalidPathException("Wrong FS: " + path
          + " and port=" + thatPort
          + ", expected: "
          + this.getUri()
          + " with port=" + thisPort);
    }
  }
  
  /**
   * Get the path-part of a pathname. Checks that URI matches this file system
   * and that the path-part is a valid name.
   * 
   * @param p path
   * 
   * @return path-part of the Path p
   */
  public String getUriPath(final Path p) {
    checkPath(p);
    String s = p.toUri().getPath();
    if (!isValidName(s)) {
      throw new InvalidPathException("Path part " + s + " from URI " + p
          + " is not a valid filename.");
    }
    return s;
  }
  
  /**
   * Make the path fully qualified to this file system
   * @param path the path.
   * @return the qualified path
   */
  public Path makeQualified(Path path) {
    checkPath(path);
    return path.makeQualified(this.getUri(), null);
  }
  
  /**
   * Some file systems like LocalFileSystem have an initial workingDir
   * that is used as the starting workingDir. For other file systems
   * like HDFS there is no built in notion of an initial workingDir.
   * 
   * @return the initial workingDir if the file system has such a notion
   *         otherwise return a null.
   */
  public Path getInitialWorkingDirectory() {
    return null;
  }
  
  /** 
   * Return the current user's home directory in this file system.
   * The default implementation returns "/user/$USER/".
   * 
   * @return current user's home directory.
   */
  public Path getHomeDirectory() {
    String username;
    try {
      username = UserGroupInformation.getCurrentUser().getShortUserName();
    } catch(IOException ex) {
      LOG.warn("Unable to get user name. Fall back to system property " +
          "user.name", ex);
      username = System.getProperty("user.name");
    }
    return new Path("/user/" + username)
        .makeQualified(getUri(), null);
  }
  
  /**
   * Return a set of server default configuration values.
   * 
   * @return server default configuration values
   * 
   * @throws IOException an I/O error occurred
   * @deprecated use {@link #getServerDefaults(Path)} instead
   */
  @Deprecated
  public abstract FsServerDefaults getServerDefaults() throws IOException; 

  /**
   * Return a set of server default configuration values based on path.
   * @param f path to fetch server defaults
   * @return server default configuration values for path
   * @throws IOException an I/O error occurred
   */
  public FsServerDefaults getServerDefaults(final Path f) throws IOException {
    return getServerDefaults();
  }

  /**
   * Return the fully-qualified path of path f resolving the path
   * through any internal symlinks or mount point
   * @param p path to be resolved
   * @return fully qualified path 
   * @throws FileNotFoundException when file not find throw.
   * @throws AccessControlException when accees control error throw.
   * @throws IOException raised on errors performing I/O.
   * @throws UnresolvedLinkException if symbolic link on path cannot be
   * resolved internally
   */
   public Path resolvePath(final Path p) throws FileNotFoundException,
           UnresolvedLinkException, AccessControlException, IOException {
     checkPath(p);
     return getFileStatus(p).getPath(); // default impl is to return the path
   }
  
  /**
   * The specification of this method matches that of
   * {@link FileContext#create(Path, EnumSet, Options.CreateOpts...)} except
   * that the Path f must be fully qualified and the permission is absolute
   * (i.e. umask has been applied).
   *
   * @param f the path.
   * @param createFlag create_flag.
   * @param opts create ops.
   * @throws AccessControlException access controll exception.
   * @throws FileAlreadyExistsException file already exception.
   * @throws FileNotFoundException file not found exception.
   * @throws ParentNotDirectoryException parent not dir exception.
   * @throws UnsupportedFileSystemException unsupported file system exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return output stream.
   */
  public final FSDataOutputStream create(final Path f,
      final EnumSet<CreateFlag> createFlag, Options.CreateOpts... opts)
      throws AccessControlException, FileAlreadyExistsException,
      FileNotFoundException, ParentNotDirectoryException,
      UnsupportedFileSystemException, UnresolvedLinkException, IOException {
    checkPath(f);
    int bufferSize = -1;
    short replication = -1;
    long blockSize = -1;
    int bytesPerChecksum = -1;
    ChecksumOpt checksumOpt = null;
    FsPermission permission = null;
    Progressable progress = null;
    Boolean createParent = null;
 
    for (CreateOpts iOpt : opts) {
      if (CreateOpts.BlockSize.class.isInstance(iOpt)) {
        if (blockSize != -1) {
          throw new HadoopIllegalArgumentException(
              "BlockSize option is set multiple times");
        }
        blockSize = ((CreateOpts.BlockSize) iOpt).getValue();
      } else if (CreateOpts.BufferSize.class.isInstance(iOpt)) {
        if (bufferSize != -1) {
          throw new HadoopIllegalArgumentException(
              "BufferSize option is set multiple times");
        }
        bufferSize = ((CreateOpts.BufferSize) iOpt).getValue();
      } else if (CreateOpts.ReplicationFactor.class.isInstance(iOpt)) {
        if (replication != -1) {
          throw new HadoopIllegalArgumentException(
              "ReplicationFactor option is set multiple times");
        }
        replication = ((CreateOpts.ReplicationFactor) iOpt).getValue();
      } else if (CreateOpts.BytesPerChecksum.class.isInstance(iOpt)) {
        if (bytesPerChecksum != -1) {
          throw new HadoopIllegalArgumentException(
              "BytesPerChecksum option is set multiple times");
        }
        bytesPerChecksum = ((CreateOpts.BytesPerChecksum) iOpt).getValue();
      } else if (CreateOpts.ChecksumParam.class.isInstance(iOpt)) {
        if (checksumOpt != null) {
          throw new  HadoopIllegalArgumentException(
              "CreateChecksumType option is set multiple times");
        }
        checksumOpt = ((CreateOpts.ChecksumParam) iOpt).getValue();
      } else if (CreateOpts.Perms.class.isInstance(iOpt)) {
        if (permission != null) {
          throw new HadoopIllegalArgumentException(
              "Perms option is set multiple times");
        }
        permission = ((CreateOpts.Perms) iOpt).getValue();
      } else if (CreateOpts.Progress.class.isInstance(iOpt)) {
        if (progress != null) {
          throw new HadoopIllegalArgumentException(
              "Progress option is set multiple times");
        }
        progress = ((CreateOpts.Progress) iOpt).getValue();
      } else if (CreateOpts.CreateParent.class.isInstance(iOpt)) {
        if (createParent != null) {
          throw new HadoopIllegalArgumentException(
              "CreateParent option is set multiple times");
        }
        createParent = ((CreateOpts.CreateParent) iOpt).getValue();
      } else {
        throw new HadoopIllegalArgumentException("Unkown CreateOpts of type " +
            iOpt.getClass().getName());
      }
    }
    if (permission == null) {
      throw new HadoopIllegalArgumentException("no permission supplied");
    }


    FsServerDefaults ssDef = getServerDefaults(f);
    if (ssDef.getBlockSize() % ssDef.getBytesPerChecksum() != 0) {
      throw new IOException("Internal error: default blockSize is" + 
          " not a multiple of default bytesPerChecksum ");
    }
    
    if (blockSize == -1) {
      blockSize = ssDef.getBlockSize();
    }

    // Create a checksum option honoring user input as much as possible.
    // If bytesPerChecksum is specified, it will override the one set in
    // checksumOpt. Any missing value will be filled in using the default.
    ChecksumOpt defaultOpt = new ChecksumOpt(
        ssDef.getChecksumType(),
        ssDef.getBytesPerChecksum());
    checksumOpt = ChecksumOpt.processChecksumOpt(defaultOpt,
        checksumOpt, bytesPerChecksum);

    if (bufferSize == -1) {
      bufferSize = ssDef.getFileBufferSize();
    }
    if (replication == -1) {
      replication = ssDef.getReplication();
    }
    if (createParent == null) {
      createParent = false;
    }

    if (blockSize % bytesPerChecksum != 0) {
      throw new HadoopIllegalArgumentException(
             "blockSize should be a multiple of checksumsize");
    }

    return this.createInternal(f, createFlag, permission, bufferSize,
      replication, blockSize, progress, checksumOpt, createParent);
  }

  /**
   * The specification of this method matches that of
   * {@link #create(Path, EnumSet, Options.CreateOpts...)} except that the opts
   * have been declared explicitly.
   *
   * @param f the path.
   * @param flag create flag.
   * @param absolutePermission absolute permission.
   * @param bufferSize buffer size.
   * @param replication replications.
   * @param blockSize block size.
   * @param progress progress.
   * @param checksumOpt check sum opt.
   * @param createParent create parent.
   * @throws AccessControlException access control exception.
   * @throws FileAlreadyExistsException file already exists exception.
   * @throws FileNotFoundException file not found exception.
   * @throws ParentNotDirectoryException parent not directory exception.
   * @throws UnsupportedFileSystemException unsupported filesystem exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return output stream.
   */
  public abstract FSDataOutputStream createInternal(Path f,
      EnumSet<CreateFlag> flag, FsPermission absolutePermission,
      int bufferSize, short replication, long blockSize, Progressable progress,
      ChecksumOpt checksumOpt, boolean createParent)
      throws AccessControlException, FileAlreadyExistsException,
      FileNotFoundException, ParentNotDirectoryException,
      UnsupportedFileSystemException, UnresolvedLinkException, IOException;

  /**
   * The specification of this method matches that of
   * {@link FileContext#mkdir(Path, FsPermission, boolean)} except that the Path
   * f must be fully qualified and the permission is absolute (i.e. 
   * umask has been applied).
   * @param dir directory.
   * @param permission permission.
   * @param createParent create parent flag.
   * @throws AccessControlException access control exception.
   * @throws FileAlreadyExistsException file already exists exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   */
  public abstract void mkdir(final Path dir, final FsPermission permission,
      final boolean createParent) throws AccessControlException,
      FileAlreadyExistsException, FileNotFoundException,
      UnresolvedLinkException, IOException;

  /**
   * The specification of this method matches that of
   * {@link FileContext#delete(Path, boolean)} except that Path f must be for
   * this file system.
   *
   * @param f the path.
   * @param recursive recursive flag.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return if successfully deleted success true, not false.
   */
  public abstract boolean delete(final Path f, final boolean recursive)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException;

  /**
   * The specification of this method matches that of
   * {@link FileContext#open(Path)} except that Path f must be for this
   * file system.
   *
   * @param f the path.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return input stream.
   */
  public FSDataInputStream open(final Path f) throws AccessControlException,
      FileNotFoundException, UnresolvedLinkException, IOException {
    return open(f, getServerDefaults(f).getFileBufferSize());
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext#open(Path, int)} except that Path f must be for this
   * file system.
   *
   * @param f the path.
   * @param bufferSize buffer size.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return if successfully open success true, not false.
   */
  public abstract FSDataInputStream open(final Path f, int bufferSize)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException;

  /**
   * The specification of this method matches that of
   * {@link FileContext#truncate(Path, long)} except that Path f must be for
   * this file system.
   *
   * @param f the path.
   * @param newLength new length.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return if successfully truncate success true, not false.
   */
  public boolean truncate(Path f, long newLength)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support truncate");
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext#setReplication(Path, short)} except that Path f must be
   * for this file system.
   *
   * @param f the path.
   * @param replication replication.
   * @return if successfully set replication success true, not false.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   */
  public abstract boolean setReplication(final Path f,
      final short replication) throws AccessControlException,
      FileNotFoundException, UnresolvedLinkException, IOException;

  /**
   * The specification of this method matches that of
   * {@link FileContext#rename(Path, Path, Options.Rename...)} except that Path
   * f must be for this file system.
   *
   * @param src src.
   * @param dst dst.
   * @param options options.
   * @throws AccessControlException access control exception.
   * @throws FileAlreadyExistsException file already exists exception.
   * @throws FileNotFoundException file not found exception.
   * @throws ParentNotDirectoryException parent not directory exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   */
  public final void rename(final Path src, final Path dst,
      final Options.Rename... options) throws AccessControlException,
      FileAlreadyExistsException, FileNotFoundException,
      ParentNotDirectoryException, UnresolvedLinkException, IOException {
    boolean overwrite = false;
    if (null != options) {
      for (Rename option : options) {
        if (option == Rename.OVERWRITE) {
          overwrite = true;
        }
      }
    }
    renameInternal(src, dst, overwrite);
  }
  
  /**
   * The specification of this method matches that of
   * {@link FileContext#rename(Path, Path, Options.Rename...)} except that Path
   * f must be for this file system and NO OVERWRITE is performed.
   * 
   * File systems that do not have a built in overwrite need implement only this
   * method and can take advantage of the default impl of the other
   * {@link #renameInternal(Path, Path, boolean)}
   *
   * @param src src.
   * @param dst dst.
   * @throws AccessControlException access control exception.
   * @throws FileAlreadyExistsException file already exists exception.
   * @throws FileNotFoundException file not found exception.
   * @throws ParentNotDirectoryException parent not directory exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   */
  public abstract void renameInternal(final Path src, final Path dst)
      throws AccessControlException, FileAlreadyExistsException,
      FileNotFoundException, ParentNotDirectoryException,
      UnresolvedLinkException, IOException;
  
  /**
   * The specification of this method matches that of
   * {@link FileContext#rename(Path, Path, Options.Rename...)} except that Path
   * f must be for this file system.
   *
   * @param src src.
   * @param dst dst.
   * @param overwrite overwrite flag.
   * @throws AccessControlException access control exception.
   * @throws FileAlreadyExistsException file already exists exception.
   * @throws FileNotFoundException file not found exception.
   * @throws ParentNotDirectoryException parent not directory exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   */
  public void renameInternal(final Path src, final Path dst,
      boolean overwrite) throws AccessControlException,
      FileAlreadyExistsException, FileNotFoundException,
      ParentNotDirectoryException, UnresolvedLinkException, IOException {
    // Default implementation deals with overwrite in a non-atomic way
    final FileStatus srcStatus = getFileLinkStatus(src);

    FileStatus dstStatus;
    try {
      dstStatus = getFileLinkStatus(dst);
    } catch (IOException e) {
      dstStatus = null;
    }
    if (dstStatus != null) {
      if (dst.equals(src)) {
        throw new FileAlreadyExistsException(
            "The source "+src+" and destination "+dst+" are the same");
      }
      if (srcStatus.isSymlink() && dst.equals(srcStatus.getSymlink())) {
        throw new FileAlreadyExistsException(
            "Cannot rename symlink "+src+" to its target "+dst);
      }
      // It's OK to rename a file to a symlink and vice versa
      if (srcStatus.isDirectory() != dstStatus.isDirectory()) {
        throw new IOException("Source " + src + " and destination " + dst
            + " must both be directories");
      }
      if (!overwrite) {
        throw new FileAlreadyExistsException("Rename destination " + dst
            + " already exists.");
      }
      // Delete the destination that is a file or an empty directory
      if (dstStatus.isDirectory()) {
        RemoteIterator<FileStatus> list = listStatusIterator(dst);
        if (list != null && list.hasNext()) {
          throw new IOException(
              "Rename cannot overwrite non empty destination directory " + dst);
        }
      }
      delete(dst, false);
    } else {
      final Path parent = dst.getParent();
      final FileStatus parentStatus = getFileStatus(parent);
      if (parentStatus.isFile()) {
        throw new ParentNotDirectoryException("Rename destination parent "
            + parent + " is a file.");
      }
    }
    renameInternal(src, dst);
  }
  
  /**
   * Returns true if the file system supports symlinks, false otherwise.
   * @return true if filesystem supports symlinks
   */
  public boolean supportsSymlinks() {
    return false;
  }
  
  /**
   * The specification of this method matches that of  
   * {@link FileContext#createSymlink(Path, Path, boolean)};
   *
   * @param target target.
   * @param link link.
   * @param createParent create parent.
   * @throws IOException raised on errors performing I/O.
   * @throws UnresolvedLinkException unresolved link exception.
   */
  public void createSymlink(final Path target, final Path link,
      final boolean createParent) throws IOException, UnresolvedLinkException {
    throw new IOException("File system does not support symlinks");    
  }

  /**
   * Partially resolves the path. This is used during symlink resolution in
   * {@link FSLinkResolver}, and differs from the similarly named method
   * {@link FileContext#getLinkTarget(Path)}.
   * @param f the path.
   * @return target path.
   * @throws IOException subclass implementations may throw IOException 
   */
  public Path getLinkTarget(final Path f) throws IOException {
    throw new AssertionError("Implementation Error: " + getClass()
        + " that threw an UnresolvedLinkException, causing this method to be"
        + " called, needs to override this method.");
  }
    
  /**
   * The specification of this method matches that of
   * {@link FileContext#setPermission(Path, FsPermission)} except that Path f
   * must be for this file system.
   *
   * @param f the path.
   * @param permission permission.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   */
  public abstract void setPermission(final Path f,
      final FsPermission permission) throws AccessControlException,
      FileNotFoundException, UnresolvedLinkException, IOException;

  /**
   * The specification of this method matches that of
   * {@link FileContext#setOwner(Path, String, String)} except that Path f must
   * be for this file system.
   *
   * @param f the path.
   * @param username username.
   * @param groupname groupname.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   */
  public abstract void setOwner(final Path f, final String username,
      final String groupname) throws AccessControlException,
      FileNotFoundException, UnresolvedLinkException, IOException;

  /**
   * The specification of this method matches that of
   * {@link FileContext#setTimes(Path, long, long)} except that Path f must be
   * for this file system.
   *
   * @param f the path.
   * @param mtime modify time.
   * @param atime access time.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   */
  public abstract void setTimes(final Path f, final long mtime,
    final long atime) throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException;

  /**
   * The specification of this method matches that of
   * {@link FileContext#getFileChecksum(Path)} except that Path f must be for
   * this file system.
   *
   * @param f the path.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return File Check sum.
   */
  public abstract FileChecksum getFileChecksum(final Path f)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException;
  
  /**
   * The specification of this method matches that of
   * {@link FileContext#getFileStatus(Path)} 
   * except that an UnresolvedLinkException may be thrown if a symlink is 
   * encountered in the path.
   *
   * @param f the path.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return File Status
   */
  public abstract FileStatus getFileStatus(final Path f)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException;

  /**
   * Synchronize client metadata state.
   * <p>
   * In some FileSystem implementations such as HDFS metadata
   * synchronization is essential to guarantee consistency of read requests
   * particularly in HA setting.
   * @throws IOException raised on errors performing I/O.
   * @throws UnsupportedOperationException Unsupported Operation Exception.
   */
  public void msync() throws IOException, UnsupportedOperationException {
    throw new UnsupportedOperationException(getClass().getCanonicalName() +
        " does not support method msync");
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext#access(Path, FsAction)}
   * except that an UnresolvedLinkException may be thrown if a symlink is
   * encountered in the path.
   *
   * @param path the path.
   * @param mode fsaction mode.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   */
  @InterfaceAudience.LimitedPrivate({"HDFS", "Hive"})
  public void access(Path path, FsAction mode) throws AccessControlException,
      FileNotFoundException, UnresolvedLinkException, IOException {
    FileSystem.checkAccessPermissions(this.getFileStatus(path), mode);
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext#getFileLinkStatus(Path)}
   * except that an UnresolvedLinkException may be thrown if a symlink is  
   * encountered in the path leading up to the final path component.
   * If the file system does not support symlinks then the behavior is
   * equivalent to {@link AbstractFileSystem#getFileStatus(Path)}.
   *
   * @param f the path.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnsupportedFileSystemException UnSupported File System Exception.
   * @throws IOException raised on errors performing I/O.
   * @return file status.
   */
  public FileStatus getFileLinkStatus(final Path f)
      throws AccessControlException, FileNotFoundException,
      UnsupportedFileSystemException, IOException {
    return getFileStatus(f);
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext#getFileBlockLocations(Path, long, long)} except that
   * Path f must be for this file system.
   *
   * @param f the path.
   * @param start start.
   * @param len length.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return BlockLocation Array.
   */
  public abstract BlockLocation[] getFileBlockLocations(final Path f,
      final long start, final long len) throws AccessControlException,
      FileNotFoundException, UnresolvedLinkException, IOException;

  /**
   * The specification of this method matches that of
   * {@link FileContext#getFsStatus(Path)} except that Path f must be for this
   * file system.
   *
   * @param f the path.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return Fs Status.
   */
  public FsStatus getFsStatus(final Path f) throws AccessControlException,
      FileNotFoundException, UnresolvedLinkException, IOException {
    // default impl gets FsStatus of root
    return getFsStatus();
  }
  
  /**
   * The specification of this method matches that of
   * {@link FileContext#getFsStatus(Path)}.
   *
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws IOException raised on errors performing I/O.
   * @return Fs Status.
   */
  public abstract FsStatus getFsStatus() throws AccessControlException,
      FileNotFoundException, IOException;

  /**
   * The specification of this method matches that of
   * {@link FileContext#listStatus(Path)} except that Path f must be for this
   * file system.
   *
   * @param f path.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return FileStatus Iterator.
   */
  public RemoteIterator<FileStatus> listStatusIterator(final Path f)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    return new RemoteIterator<FileStatus>() {
      private int i = 0;
      private FileStatus[] statusList = listStatus(f);
      
      @Override
      public boolean hasNext() {
        return i < statusList.length;
      }
      
      @Override
      public FileStatus next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return statusList[i++];
      }
    };
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext#listLocatedStatus(Path)} except that Path f 
   * must be for this file system.
   *
   * In HDFS implementation, the BlockLocation of returned LocatedFileStatus
   * will have different formats for replicated and erasure coded file. Please
   * refer to {@link FileSystem#getFileBlockLocations(FileStatus, long, long)}
   * for more details.
   *
   * @param f the path.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return FileStatus Iterator.
   */
  public RemoteIterator<LocatedFileStatus> listLocatedStatus(final Path f)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    return new RemoteIterator<LocatedFileStatus>() {
      private RemoteIterator<FileStatus> itor = listStatusIterator(f);
      
      @Override
      public boolean hasNext() throws IOException {
        return itor.hasNext();
      }
      
      @Override
      public LocatedFileStatus next() throws IOException {
        if (!hasNext()) {
          throw new NoSuchElementException("No more entry in " + f);
        }
        FileStatus result = itor.next();
        BlockLocation[] locs = null;
        if (result.isFile()) {
          locs = getFileBlockLocations(
              result.getPath(), 0, result.getLen());
        }
        return new LocatedFileStatus(result, locs);
      }
    };
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext.Util#listStatus(Path)} except that Path f must be 
   * for this file system.
   * @param f the path.
   * @throws AccessControlException access control exception.
   * @throws FileNotFoundException file not found exception.
   * @throws UnresolvedLinkException unresolved link exception.
   * @throws IOException raised on errors performing I/O.
   * @return FileStatus Iterator.
   */
  public abstract FileStatus[] listStatus(final Path f)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException;

  /**
   * @return an iterator over the corrupt files under the given path
   * (may contain duplicates if a file has more than one corrupt block)
   * @param path the path.
   * @throws IOException raised on errors performing I/O.
   */
  public RemoteIterator<Path> listCorruptFileBlocks(Path path)
    throws IOException {
    throw new UnsupportedOperationException(getClass().getCanonicalName() +
                                            " does not support" +
                                            " listCorruptFileBlocks");
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext#setVerifyChecksum(boolean, Path)} except that Path f
   * must be for this file system.
   *
   * @param verifyChecksum verify check sum flag.
   * @throws AccessControlException access control exception.
   * @throws IOException raised on errors performing I/O.
   */
  public abstract void setVerifyChecksum(final boolean verifyChecksum)
      throws AccessControlException, IOException;
  
  /**
   * Get a canonical name for this file system.
   * @return a URI string that uniquely identifies this file system
   */
  public String getCanonicalServiceName() {
    return SecurityUtil.buildDTServiceName(getUri(), getUriDefaultPort());
  }
  
  /**
   * Get one or more delegation tokens associated with the filesystem. Normally
   * a file system returns a single delegation token. A file system that manages
   * multiple file systems underneath, could return set of delegation tokens for
   * all the file systems it manages
   * 
   * @param renewer the account name that is allowed to renew the token.
   * @return List of delegation tokens.
   *   If delegation tokens not supported then return a list of size zero.
   * @throws IOException raised on errors performing I/O.
   */
  @InterfaceAudience.LimitedPrivate( { "HDFS", "MapReduce" })
  public List<Token<?>> getDelegationTokens(String renewer) throws IOException {
    return Collections.emptyList();
  }

  /**
   * Modifies ACL entries of files and directories.  This method can add new ACL
   * entries or modify the permissions on existing ACL entries.  All existing
   * ACL entries that are not specified in this call are retained without
   * changes.  (Modifications are merged into the current ACL.)
   *
   * @param path Path to modify
   * @param aclSpec List{@literal <AclEntry>} describing modifications
   * @throws IOException if an ACL could not be modified
   */
  public void modifyAclEntries(Path path, List<AclEntry> aclSpec)
      throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support modifyAclEntries");
  }

  /**
   * Removes ACL entries from files and directories.  Other ACL entries are
   * retained.
   *
   * @param path Path to modify
   * @param aclSpec List{@literal <AclEntry>} describing entries to remove
   * @throws IOException if an ACL could not be modified
   */
  public void removeAclEntries(Path path, List<AclEntry> aclSpec)
      throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support removeAclEntries");
  }

  /**
   * Removes all default ACL entries from files and directories.
   *
   * @param path Path to modify
   * @throws IOException if an ACL could not be modified
   */
  public void removeDefaultAcl(Path path)
      throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support removeDefaultAcl");
  }

  /**
   * Removes all but the base ACL entries of files and directories.  The entries
   * for user, group, and others are retained for compatibility with permission
   * bits.
   *
   * @param path Path to modify
   * @throws IOException if an ACL could not be removed
   */
  public void removeAcl(Path path)
      throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support removeAcl");
  }

  /**
   * Fully replaces ACL of files and directories, discarding all existing
   * entries.
   *
   * @param path Path to modify
   * @param aclSpec List{@literal <AclEntry>} describing modifications, must
   * include entries for user, group, and others for compatibility with
   * permission bits.
   * @throws IOException if an ACL could not be modified
   */
  public void setAcl(Path path, List<AclEntry> aclSpec) throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support setAcl");
  }

  /**
   * Gets the ACLs of files and directories.
   *
   * @param path Path to get
   * @return RemoteIterator{@literal <AclStatus>} which returns each AclStatus
   * @throws IOException if an ACL could not be read
   */
  public AclStatus getAclStatus(Path path) throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support getAclStatus");
  }

  /**
   * Set an xattr of a file or directory.
   * The name must be prefixed with the namespace followed by ".". For example,
   * "user.attr".
   * <p>
   * Refer to the HDFS extended attributes user documentation for details.
   *
   * @param path Path to modify
   * @param name xattr name.
   * @param value xattr value.
   * @throws IOException raised on errors performing I/O.
   */
  public void setXAttr(Path path, String name, byte[] value)
      throws IOException {
    setXAttr(path, name, value, EnumSet.of(XAttrSetFlag.CREATE,
        XAttrSetFlag.REPLACE));
  }

  /**
   * Set an xattr of a file or directory.
   * The name must be prefixed with the namespace followed by ".". For example,
   * "user.attr".
   * <p>
   * Refer to the HDFS extended attributes user documentation for details.
   *
   * @param path Path to modify
   * @param name xattr name.
   * @param value xattr value.
   * @param flag xattr set flag
   * @throws IOException raised on errors performing I/O.
   */
  public void setXAttr(Path path, String name, byte[] value,
      EnumSet<XAttrSetFlag> flag) throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support setXAttr");
  }

  /**
   * Get an xattr for a file or directory.
   * The name must be prefixed with the namespace followed by ".". For example,
   * "user.attr".
   * <p>
   * Refer to the HDFS extended attributes user documentation for details.
   *
   * @param path Path to get extended attribute
   * @param name xattr name.
   * @return byte[] xattr value.
   * @throws IOException raised on errors performing I/O.
   */
  public byte[] getXAttr(Path path, String name) throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support getXAttr");
  }

  /**
   * Get all of the xattrs for a file or directory.
   * Only those xattrs for which the logged-in user has permissions to view
   * are returned.
   * <p>
   * Refer to the HDFS extended attributes user documentation for details.
   *
   * @param path Path to get extended attributes
   *
   * @return {@literal Map<String, byte[]>} describing the XAttrs of the file
   * or directory
   * @throws IOException raised on errors performing I/O.
   */
  public Map<String, byte[]> getXAttrs(Path path) throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support getXAttrs");
  }

  /**
   * Get all of the xattrs for a file or directory.
   * Only those xattrs for which the logged-in user has permissions to view
   * are returned.
   * <p>
   * Refer to the HDFS extended attributes user documentation for details.
   *
   * @param path Path to get extended attributes
   * @param names XAttr names.
   * @return {@literal Map<String, byte[]>} describing the XAttrs of the file
   * or directory
   * @throws IOException raised on errors performing I/O.
   */
  public Map<String, byte[]> getXAttrs(Path path, List<String> names)
      throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support getXAttrs");
  }

  /**
   * Get all of the xattr names for a file or directory.
   * Only the xattr names for which the logged-in user has permissions to view
   * are returned.
   * <p>
   * Refer to the HDFS extended attributes user documentation for details.
   *
   * @param path Path to get extended attributes
   * @return {@literal Map<String, byte[]>} describing the XAttrs of the file
   * or directory
   * @throws IOException raised on errors performing I/O.
   */
  public List<String> listXAttrs(Path path)
          throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
            + " doesn't support listXAttrs");
  }

  /**
   * Remove an xattr of a file or directory.
   * The name must be prefixed with the namespace followed by ".". For example,
   * "user.attr".
   * <p>
   * Refer to the HDFS extended attributes user documentation for details.
   *
   * @param path Path to remove extended attribute
   * @param name xattr name
   * @throws IOException raised on errors performing I/O.
   */
  public void removeXAttr(Path path, String name) throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support removeXAttr");
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext#createSnapshot(Path, String)}.
   *
   * @param path the path.
   * @param snapshotName snapshot name.
   * @throws IOException raised on errors performing I/O.
   * @return path.
   */
  public Path createSnapshot(final Path path, final String snapshotName)
      throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support createSnapshot");
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext#renameSnapshot(Path, String, String)}.
   *
   * @param path the path.
   * @param snapshotOldName snapshot old name.
   * @param snapshotNewName snapshot new name.
   * @throws IOException raised on errors performing I/O.
   */
  public void renameSnapshot(final Path path, final String snapshotOldName,
      final String snapshotNewName) throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support renameSnapshot");
  }

  /**
   * The specification of this method matches that of
   * {@link FileContext#deleteSnapshot(Path, String)}.
   *
   * @param snapshotDir snapshot dir.
   * @param snapshotName snapshot name.
   * @throws IOException raised on errors performing I/O.
   */
  public void deleteSnapshot(final Path snapshotDir, final String snapshotName)
      throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support deleteSnapshot");
  }

  /**
   * Set the source path to satisfy storage policy.
   * @param path The source path referring to either a directory or a file.
   * @throws IOException raised on errors performing I/O.
   */
  public void satisfyStoragePolicy(final Path path) throws IOException {
    throw new UnsupportedOperationException(
        getClass().getSimpleName() + " doesn't support satisfyStoragePolicy");
  }

  /**
   * Set the storage policy for a given file or directory.
   *
   * @param path file or directory path.
   * @param policyName the name of the target storage policy. The list
   *                   of supported Storage policies can be retrieved
   *                   via {@link #getAllStoragePolicies}.
   * @throws IOException raised on errors performing I/O.
   */
  public void setStoragePolicy(final Path path, final String policyName)
      throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support setStoragePolicy");
  }


  /**
   * Unset the storage policy set for a given file or directory.
   * @param src file or directory path.
   * @throws IOException raised on errors performing I/O.
   */
  public void unsetStoragePolicy(final Path src) throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support unsetStoragePolicy");
  }

  /**
   * Retrieve the storage policy for a given file or directory.
   *
   * @param src file or directory path.
   * @return storage policy for give file.
   * @throws IOException raised on errors performing I/O.
   */
  public BlockStoragePolicySpi getStoragePolicy(final Path src)
      throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support getStoragePolicy");
  }

  /**
   * Retrieve all the storage policies supported by this file system.
   *
   * @return all storage policies supported by this filesystem.
   * @throws IOException raised on errors performing I/O.
   */
  public Collection<? extends BlockStoragePolicySpi> getAllStoragePolicies()
      throws IOException {
    throw new UnsupportedOperationException(getClass().getSimpleName()
        + " doesn't support getAllStoragePolicies");
  }

  @Override //Object
  public int hashCode() {
    return myUri.hashCode();
  }
  
  @Override //Object
  public boolean equals(Object other) {
    if (!(other instanceof AbstractFileSystem)) {
      return false;
    }
    return myUri.equals(((AbstractFileSystem) other).myUri);
  }

  /**
   * Open a file with the given set of options.
   * The base implementation performs a blocking
   * call to {@link #open(Path, int)}in this call;
   * the actual outcome is in the returned {@code CompletableFuture}.
   * This avoids having to create some thread pool, while still
   * setting up the expectation that the {@code get()} call
   * is needed to evaluate the result.
   * @param path path to the file
   * @param parameters open file parameters from the builder.
   * @return a future which will evaluate to the opened file.
   * @throws IOException failure to resolve the link.
   * @throws IllegalArgumentException unknown mandatory key
   */
  public CompletableFuture<FSDataInputStream> openFileWithOptions(Path path,
      final OpenFileParameters parameters) throws IOException {
    AbstractFSBuilderImpl.rejectUnknownMandatoryKeys(
        parameters.getMandatoryKeys(),
        Collections.emptySet(),
        "for " + path);
    return LambdaUtils.eval(
        new CompletableFuture<>(), () ->
            open(path, parameters.getBufferSize()));
  }

  public boolean hasPathCapability(final Path path,
      final String capability)
      throws IOException {
    switch (validatePathCapabilityArgs(makeQualified(path), capability)) {
    case CommonPathCapabilities.FS_SYMLINKS:
      // delegate to the existing supportsSymlinks() call.
      return supportsSymlinks();
    default:
      // the feature is not implemented.
      return false;
    }
  }

  /**
   * Create a multipart uploader.
   * @param basePath file path under which all files are uploaded
   * @return a MultipartUploaderBuilder object to build the uploader
   * @throws IOException if some early checks cause IO failures.
   * @throws UnsupportedOperationException if support is checked early.
   */
  @InterfaceStability.Unstable
  public MultipartUploaderBuilder createMultipartUploader(Path basePath)
      throws IOException {
    methodNotSupported();
    return null;
  }

  /**
   * Return path of the enclosing root for a given path
   * The enclosing root path is a common ancestor that should be used for temp and staging dirs
   * as well as within encryption zones and other restricted directories.
   *
   * Call makeQualified on the param path to ensure its part of the correct filesystem
   *
   * @param path file path to find the enclosing root path for
   * @return a path to the enclosing root
   * @throws IOException early checks like failure to resolve path cause IO failures
   */
  @InterfaceAudience.Public
  @InterfaceStability.Unstable
  public Path getEnclosingRoot(Path path) throws IOException {
    makeQualified(path);
    return makeQualified(new Path("/"));
  }

  /**
   * Helper method that throws an {@link UnsupportedOperationException} for the
   * current {@link FileSystem} method being called.
   */
  protected final void methodNotSupported() {
    // The order of the stacktrace elements is (from top to bottom):
    //   - java.lang.Thread.getStackTrace
    //   - org.apache.hadoop.fs.FileSystem.methodNotSupported
    //   - <the FileSystem method>
    // therefore, to find out the current method name, we use the element at
    // index 2.
    String name = Thread.currentThread().getStackTrace()[2].getMethodName();
    throw new UnsupportedOperationException(getClass().getCanonicalName() +
        " does not support method " + name);
  }
}
