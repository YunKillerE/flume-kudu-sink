
Asynchronous Native Java Client for Kudu

# add by yunchen

>注意：编译时maven版本为3.3.9，如果是3.0.9的话编译无法通过，尤其是用idea编译的时候注意更改默认版本

## 1，二次开发背景

官方的sink只支持某一列作为主键，而我们这里的需求有：

1. 主键可能是随机数
2. 主键可能是随机数+字段
3. 主键可能是字段1+字段2

目前实现了这三种，至于其他情况可以自行添加

## 2，自定义组件说明

增加了三个配置选项：

```
at.sinks.sink1.producer.customKey = true
at.sinks.sink1.producer.keyName = movieid,userid
at.sinks.sink1.producer.priKey = prikey
```

customKey：是否开启自定义主键，为true或false，当为false时就是官方默认的方式

keyName：主键名称，对应着三种方式

  1. 主键为随机数，值为uuid，其实这种也没什么用，供测试，建表时主键名称必须为uuid
  2. 主键为随机数+字段，值为uuid+字段名称
  3. 主键为字段1+字段2，值为字段1+字段2

priKey：当上面为第三种情况时，此字段才有用，值为主键名称

## 3，配置参考

> wget --no-check-certificate http://raw.githubusercontent.com/bosshart/kuduscreencast/master/integrations/flume_ratings.tsv


1. 第一种情况实例
```
at.sources  = source1
at.channels = channel1
at.sinks    = sink1

at.sources.source1.type = spooldir
at.sources.source1.spoolDir = /home/demo/ratings/
at.sources.source1.fileHeader = false
at.sources.source1.channels = channel1

at.channels.channel1.type                = memory
at.channels.channel1.capacity            = 10000
at.channels.channel1.transactionCapacity = 1000

at.sinks.sink1.type = org.apache.kudu.flume.sink.KuduSink
at.sinks.sink1.masterAddresses = cmname1
at.sinks.sink1.tableName = impala::default.streaming_user_ratings
at.sinks.sink1.channel = channel1
at.sinks.sink1.batchSize = 50
at.sinks.sink1.producer = org.apache.kudu.flume.sink.RegexpKuduOperationsProducerKeySet
at.sinks.sink1.producer.pattern = (?<movieid>\\\d+)\\\t(?<userid>\\\d+)\\\t(?<rating>\\\d+)\\\t(?<movietitle>.*)
at.sinks.sink1.customKey = false
```

impala：
```
[cmserver:21000] > show create table streaming_user_ratings;
Query: show create table streaming_user_ratings
+----------------------------------------------------------------------------------+
| result                                                                           |
+----------------------------------------------------------------------------------+
| CREATE TABLE default.streaming_user_ratings (                                    |
|   movieid INT NOT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,   |
|   userid INT NOT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,    |
|   rating INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,        |
|   movietitle STRING NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION, |
|   PRIMARY KEY (movieid, userid)                                                  |
| )                                                                                |
| PARTITION BY HASH (movieid) PARTITIONS 4                                         |
| STORED AS KUDU                                                                   |
| TBLPROPERTIES ('kudu.master_addresses'='cmname1')                                |
+----------------------------------------------------------------------------------+
```

2. 第二种情况实例
```
..........

at.sinks.sink1.type = org.apache.kudu.flume.sink.KuduSink
at.sinks.sink1.masterAddresses = cmname1
at.sinks.sink1.tableName = impala::default.streaming_user_ratings_uuid
at.sinks.sink1.channel = channel1
at.sinks.sink1.batchSize = 50
at.sinks.sink1.producer = org.apache.kudu.flume.sink.RegexpKuduOperationsProducerKeySet
at.sinks.sink1.producer.pattern = (?<movieid>\\\d+)\\\t(?<userid>\\\d+)\\\t(?<rating>\\\d+)\\\t(?<movietitle>.*)
at.sinks.sink1.producer.customKey = true
at.sinks.sink1.producer.keyName = uuid
```

impala:
```
[cmserver:21000] > show create table streaming_user_ratings_uuid;
Query: show create table streaming_user_ratings_uuid
+----------------------------------------------------------------------------------+
| result                                                                           |
+----------------------------------------------------------------------------------+
| CREATE TABLE default.streaming_user_ratings_uuid (                               |
|   uuid STRING NOT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,   |
|   movieid INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,       |
|   userid INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,        |
|   rating INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,        |
|   movietitle STRING NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION, |
|   PRIMARY KEY (uuid)                                                             |
| )                                                                                |
| PARTITION BY HASH (uuid) PARTITIONS 4                                            |
| STORED AS KUDU                                                                   |
| TBLPROPERTIES ('kudu.master_addresses'='cmname1')                                |
+----------------------------------------------------------------------------------+
```

3. 第三种情况实例
```
at.sinks.sink1.type = org.apache.kudu.flume.sink.KuduSink
at.sinks.sink1.masterAddresses = cmname1
at.sinks.sink1.tableName = impala::default.streaming_user_ratings_uuidkey
at.sinks.sink1.channel = channel1
at.sinks.sink1.batchSize = 50
at.sinks.sink1.producer = org.apache.kudu.flume.sink.RegexpKuduOperationsProducerKeySet
at.sinks.sink1.producer.pattern = (?<movieid>\\\d+)\\\t(?<userid>\\\d+)\\\t(?<rating>\\\d+)\\\t(?<movietitle>.*)
at.sinks.sink1.producer.customKey = true
at.sinks.sink1.producer.keyName = movieid
```

impala:
```
[cmserver:21000] > show create table streaming_user_ratings_uuidkey;
Query: show create table streaming_user_ratings_uuidkey
+-----------------------------------------------------------------------------------+
| result                                                                            |
+-----------------------------------------------------------------------------------+
| CREATE TABLE default.streaming_user_ratings_uuidkey (                             |
|   movieid STRING NOT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION, |
|   userid INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,         |
|   rating INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,         |
|   movietitle STRING NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,  |
|   PRIMARY KEY (movieid)                                                           |
| )                                                                                 |
| PARTITION BY HASH (movieid) PARTITIONS 4                                          |
| STORED AS KUDU                                                                    |
| TBLPROPERTIES ('kudu.master_addresses'='cmname1')                                 |
+-----------------------------------------------------------------------------------+
```

4. 第四种情况实例
```
at.sinks.sink1.type = org.apache.kudu.flume.sink.KuduSink
at.sinks.sink1.masterAddresses = cmname1
at.sinks.sink1.tableName = impala::default.streaming_user_ratings_key1key2
at.sinks.sink1.channel = channel1
at.sinks.sink1.batchSize = 50
at.sinks.sink1.producer = org.apache.kudu.flume.sink.RegexpKuduOperationsProducerKeySet
at.sinks.sink1.producer.pattern = (?<movieid>\\\d+)\\\t(?<userid>\\\d+)\\\t(?<rating>\\\d+)\\\t(?<movietitle>.*)
at.sinks.sink1.producer.customKey = true
at.sinks.sink1.producer.keyName = movieid,userid
at.sinks.sink1.producer.priKey = prikey
```

impala:
```
[cmserver:21000] > show create table streaming_user_ratings_key1key2;
Query: show create table streaming_user_ratings_key1key2
+----------------------------------------------------------------------------------+
| result                                                                           |
+----------------------------------------------------------------------------------+
| CREATE TABLE default.streaming_user_ratings_key1key2 (                           |
|   prikey STRING NOT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION, |
|   movieid STRING NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,    |
|   userid INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,        |
|   rating INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,        |
|   movietitle STRING NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION, |
|   PRIMARY KEY (prikey)                                                           |
| )                                                                                |
| PARTITION BY HASH (prikey) PARTITIONS 4                                          |
| STORED AS KUDU                                                                   |
| TBLPROPERTIES ('kudu.master_addresses'='cmname1')                                |
+----------------------------------------------------------------------------------+
```

System Requirements
------------------------------------------------------------

- Java 7
- Maven 3
- MIT Kerberos (krb5)

Building the Client
------------------------------------------------------------

$ mvn package -DskipTests

The client jar will can then be found at kudu-client/target.

Running the Tests
------------------------------------------------------------

The unit tests will start a master and a tablet
server using the flags file located in the src/test/resources/
directory. The tests will locate the master and tablet server
binaries by looking in 'build/latest/bin' from the root of
the git repository. If you have recently built the C++ code
for Kudu, those should be present already.

Once everything is setup correctly, run:

$ mvn test

If for some reason the binaries aren't in the expected location
as shown above, you can pass
-DbinDir=/path/to/directory.

Integration tests, including tests which cover Hadoop integration,
may be run with:

$ mvn verify

Building the Kudu-Spark integration for Spark 2.x with Scala 2.11
------------------------------------------------------------

The Spark integration builds for Spark 1.x and Scala 2.10 by default.
Additionally, there is a build profile available for Spark 2.x with
Scala 2.11: from the kudu-spark directory, run

$ mvn clean package -P spark2_2.11

The two artifactIds are

1. kudu-spark_2.10 for Spark 1.x with Scala 2.10
2. kudu-spark2_2.11 for Spark 2.x with Scala 2.11

State of Eclipse integration
------------------------------------------------------------

Maven projects can be integrated with Eclipse in one of two
ways:

1. Import a Maven project using Eclipse's m2e plugin.
2. Generate Eclipse project files using maven's
   maven-eclipse-plugin plugin.

Each approach has its own pros and cons.

## m2e integration (Eclipse to Maven)

The m2e approach is generally recommended as m2e is still
under active development, unlike maven-eclipse-plugin. Much
of the complexity comes from how m2e maps maven lifecycle
phases to Eclipse build actions. The problem is that m2e
must be told what to do with each maven plugin, and this
information is either conveyed through explicit mapping
metadata found in pom.xml, or in an m2e "extension". m2e
ships with extensions for some of the common maven plugins,
but not for maven-antrun-plugin or maven-protoc-plugin. The
explicit metadata mapping found in kudu-client/pom.xml has
placated m2e in both cases (in Eclipse see
kudu-client->Properties->Maven->Lifecycle Mapping).
Nevertheless, maven-protoc-plugin isn't being run correctly.

To work around this, you can download, build, and install a
user-made m2e extension for maven-protoc-plugin:

  http://www.masterzen.fr/2011/12/25/protobuf-maven-m2e-and-eclipse-are-on-a-boat

See http://wiki.eclipse.org/M2E_plugin_execution_not_covered
for far more excruciating detail.

## maven-eclipse-plugin (Maven to Eclipse)

The maven-eclipse-plugin approach, despite being old
fashioned and largely unsupported, is easier to use. The
very first time you want to use it, run the following:

$ mvn -Declipse.workspace=<path-to-eclipse-workspace> eclipse:configure-workspace

This will add the M2_REPO classpath variable to Eclipse. You
can verify this in
Preferences->Java->Build Path->Classpath Variables. It
should be set to `/home/<user>/.m2/repository`.

To generate the Eclipse project files, run:

$ mvn eclipse:eclipse

If you want to look at Javadoc/source in Eclipse for
dependent artifacts, run:

$ mvn eclipse:eclipse -DdownloadJavadocs=true -DdownloadSources=true

So what's the problem with maven-eclipse-plugin? The issue
lies with maven-protoc-plugin. Because all of our .proto
files are in src/kudu, the "resource path" in
maven-protoc-plugin must be absolute and prefixed with
${project.baseDir). This absolute path is copied verbatim
to an Eclipse .classpath <classpathentry/>, and Eclipse
doesn't know what to do with it, causing it avoid building
kudu-client altogether. Other plugins (like
maven-avro-plugin) don't seem to have this problem, so it's
likely a bug in maven-protoc-plugin.

There's a simple workaround: delete the errant folder within
Eclipse and refresh the kudu-client project.
