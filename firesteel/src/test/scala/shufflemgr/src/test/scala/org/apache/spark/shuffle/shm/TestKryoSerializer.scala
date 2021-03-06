/*
 * (c) Copyright 2016 Hewlett Packard Enterprise Development LP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.spark.shuffle.shm

import java.nio.ByteBuffer

import org.scalatest.FunSuite
import org.apache.spark.{SparkEnv, SparkContext, LocalSparkContext, SparkConf, Logging}
import org.apache.spark.serializer._
import com.hp.hpl.firesteel.shuffle._
import com.esotericsoftware.kryo.Kryo;
import org.apache.spark.serializer._

import scala.collection.mutable.ArrayBuffer

class TestKryoSerializer extends FunSuite with LocalSparkContext with Logging {

  private def getThreadLocalShuffleResource(conf: SparkConf):
      ThreadLocalShuffleResourceHolder.ShuffleResource = {
        val SERIALIZATION_BUFFER_SIZE: Int =
                    conf.getInt("spark.shuffle.shm.serializer.buffer.max.mb",64)*1024*1024;
        val resourceHolder= new ThreadLocalShuffleResourceHolder()
        var shuffleResource = resourceHolder.getResource()
       if (shuffleResource == null) {
         val kryoInstance =  new KryoSerializer(SparkEnv.get.conf).newKryo(); //per-thread
         val serializationBuffer = ByteBuffer.allocateDirect(SERIALIZATION_BUFFER_SIZE)
         resourceHolder.initilaze(kryoInstance, serializationBuffer)
         shuffleResource = resourceHolder.getResource()
       }
       shuffleResource
  }

  //use variable sc instead.
  ignore("kryo serializer registration") {

    val conf = new SparkConf(false)

    //to supress a null-pointer from running the test case.
    conf.set("spark.shuffle.manager", "shm")
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    //conf.set("spark.kryo.registrator",  classOf[MyRegistrator].getName)

    sc = new SparkContext("local", "test", conf)

    val shuffleManager = SparkEnv.get.shuffleManager

    assert (shuffleManager.isInstanceOf [ShmShuffleManager])

    sc.stop()
  }

  //NOTE: this test works.
  ignore("kryo serialization deserialization test  with class registration and direct kryo object creation") {
    val conf = new SparkConf(false)

    //to supress a null-pointer from running the test case.
    conf.set("spark.shuffle.manager", "shm")
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    //conf.set("spark.kryo.registrator", classOf[MyRegistrator].getName)
    //serialization buffer, to be set as the string.
    conf.set("spark.shuffle.shm.serializer.buffer.max.mb", "64");

    sc = new SparkContext("local", "test", conf)

    logInfo("I am done with configuration setting, proceed to actual test logic.")

    //val threadLocalResourcs = getThreadLocalShuffleResource(conf)
    //val kryoSerializerSide= (new KryoSerializer (conf)).newKryo()
    val kryoSerializerSide = new Kryo();
    kryoSerializerSide.register(classOf[RankingsClass])

    //create a direct bytebuffer:
    val bufferSize = 1*1024*1024; // 1M bytes
    val byteBuffer =  ByteBuffer.allocateDirect(bufferSize);
    val serializer =
      new MapSHMShuffleStore.LocalKryoByteBufferBasedSerializer (kryoSerializerSide, byteBuffer);

    //val serializer =
     // new MapSHMShuffleStore.LocalKryoByteBufferBasedSerializer(
    //    threadLocalResourcs.getKryoInstance(), threadLocalResourcs.getByteBuffer())
    //threadLocalResourcs.getKryoInstance().register(RankingsClass.getClass())
    serializer.init();

    serializer.writeClass(classOf[RankingsClass])

    logInfo("I am here!!")

    val numberOfObjects = 1
    val objectList = new ArrayBuffer[RankingsClass]();
    for (i <- 0 to numberOfObjects) {
      objectList += new RankingsClass(i, "hello" + i, i + 1)
    }

    logInfo("total object list size is: " + objectList.length)

    var icount =0;
    objectList.foreach (obj => {
       serializer.writeObject(obj)
       println("type of the object is: " + obj.getClass.getName())
                            icount=icount+1
                           })

    serializer.flush();

    logInfo("total number of objects serialized is: " + icount)


    //val kryoDeserializerSide= ( new KryoSerializer (conf)).newKryo()
    val kryoDeserializerSide= new Kryo();
    kryoDeserializerSide.register(classOf[RankingsClass])

    val serializedResultHolder = serializer.getByteBuffer();

    val deserializer =
      new ReduceSHMShuffleStore.LocalKryoByteBufferBasedDeserializer(
               kryoDeserializerSide, serializedResultHolder);
    deserializer.init();
    val retrievedClass = deserializer.readClass()

    assert(retrievedClass.equals(classOf[RankingsClass]))

    logInfo("deserializer retrieves the object class definition is: " + retrievedClass.getName())

    var count = 0
    var hasNext = true

    val retrievedObjectList = new ArrayBuffer[RankingsClass]()
    while (hasNext) {
      try {
        val obj = deserializer.readObject()
        if (obj.isInstanceOf[RankingsClass]) {
          retrievedObjectList += obj.asInstanceOf[RankingsClass]
          val x = obj.asInstanceOf[RankingsClass]
          logInfo("object retrieved is: " + x + " " + x.pageurl + " " + x.avgduration)
          count = count + 1
        }
      }
      catch {
        case t: Throwable => {
          //NOTE: the deserialization error can be very serve, as you get out of the acutual byte-byffer.
          //in the real C++ shuffle engine. we know we are at the end of the deserialization.
          logError("fails to further retrieve objects. Done. no more objects")
          hasNext = false;
        }
      }
    }

    assert(count == retrievedObjectList.size)

    var p: Int = 0;
    val objectArray = objectList.toArray[RankingsClass]
    retrievedObjectList.foreach(obj => {
      assert(obj.equals(objectArray(p)))
      logInfo("object " + p + " equal testing passed")
      p = p + 1
      }
    )

  }

 ignore ("kryo serialization deserialization test  with class registration and  kryo object creation from Spark") {
    val conf = new SparkConf(false)

    //to supress a null-pointer from running the test case.
    conf.set("spark.shuffle.manager", "shm")
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.set("spark.kryo.registrationRequired", "true")
    //conf.registerKryoClasses(Array(classOf[RankingsClass]))
    conf.set("spark.kryo.registrator", classOf[MyRegistrator].getName)
    //serialization buffer, to be set as the string.
    conf.set("spark.shuffle.shm.serializer.buffer.max.mb", "64");

    sc = new SparkContext("local", "test", conf)

    logInfo("I am done with configuration setting, proceed to actual test logic.")

    //val threadLocalResourcs = getThreadLocalShuffleResource(conf)
    val kryoSerializerSide= (new KryoSerializer (conf)).newKryo()
    //val kryoSerializerSide = new Kryo();
    //kryoSerializerSide.register(classOf[RankingsClass])

    //create a direct bytebuffer:
    val bufferSize = 1*1024*1024; // 1M bytes
    val byteBuffer =  ByteBuffer.allocateDirect(bufferSize);
    val serializer =
      new MapSHMShuffleStore.LocalKryoByteBufferBasedSerializer (kryoSerializerSide, byteBuffer);

    //val serializer =
    // new MapSHMShuffleStore.LocalKryoByteBufferBasedSerializer(
    //    threadLocalResourcs.getKryoInstance(), threadLocalResourcs.getByteBuffer())
    //threadLocalResourcs.getKryoInstance().register(RankingsClass.getClass())
    serializer.init();

    serializer.writeClass(classOf[RankingsClass])

    logInfo("I am here!!")

    val numberOfObjects = 20
    val objectList = new ArrayBuffer[RankingsClass]();
    for (i <- 0 to numberOfObjects) {
      objectList += new RankingsClass(i, "hello" + i, i + 1)
    }

    logInfo("total object list size is: " + objectList.length)

    var icount =0;
    objectList.foreach (obj => {
      serializer.writeObject(obj)
      println("type of the object is: " + obj.getClass.getName())
      icount=icount+1
    })

    serializer.flush();

    logInfo("total number of objects serialized is: " + icount)

    val kryoDeserializerSide= ( new KryoSerializer (conf)).newKryo()
    //val kryoDeserializerSide= new Kryo();
    //kryoDeserializerSide.register(classOf[RankingsClass])

    val serializedResultHolder = serializer.getByteBuffer();

    logInfo("serializer has the byte buffer size: " + serializedResultHolder.position());

    val deserializer =
      new ReduceSHMShuffleStore.LocalKryoByteBufferBasedDeserializer(
        kryoDeserializerSide, serializedResultHolder);
    deserializer.init();
    val retrievedClass = deserializer.readClass()

    assert(retrievedClass.equals(classOf[RankingsClass]))

    logInfo("deserializer retrieves the object class definition is: " + retrievedClass.getName())

    var count = 0
    var hasNext = true

    val retrievedObjectList = new ArrayBuffer[RankingsClass]()
    while (hasNext) {
      try {
        val obj = deserializer.readObject()
        if (obj.isInstanceOf[RankingsClass]) {
          val x = obj.asInstanceOf[RankingsClass]
          logInfo("object retrieved is: " + x.pagerank + " " + x.pageurl + " " + x.avgduration)
          retrievedObjectList += obj.asInstanceOf[RankingsClass]
          count = count + 1
        }
      }
      catch {
        case t: Throwable => {
          //NOTE: the deserialization error can be very serve, as you get out of the acutual byte-byffer.
          //in the real C++ shuffle engine. we know we are at the end of the deserialization.
          logError("fails to further retrieve objects. Done. no more objects")
          hasNext = false;
        }
      }
    }

    assert(count == retrievedObjectList.size)

    var p: Int = 0;
    val objectArray = objectList.toArray[RankingsClass]
    retrievedObjectList.foreach(obj => {
      assert(obj.equals(objectArray(p)))
      p = p + 1
    }
    )

  }


 ignore  ("kryo serialization deserialization test without class registration and kryo object creation from Spark") {
    val conf = new SparkConf(false)

    //to supress a null-pointer from running the test case.
    conf.set("spark.shuffle.manager", "shm")
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    //conf.set("spark.kryo.registrationRequired", "true")
    //conf.registerKryoClasses(Array(classOf[RankingsClass]))
    //conf.set("spark.kryo.registrator", classOf[MyRegistrator].getName)
    //serialization buffer, to be set as the string.
    conf.set("spark.shuffle.shm.serializer.buffer.max.mb", "64");

    sc = new SparkContext("local", "test", conf)

    logInfo("I am done with configuration setting, proceed to actual test logic.")

    //val threadLocalResourcs = getThreadLocalShuffleResource(conf)
    val kryoSerializerSide= (new KryoSerializer (conf)).newKryo()
    //val kryoSerializerSide = new Kryo();
    //kryoSerializerSide.register(classOf[RankingsClass])

    //create a direct bytebuffer:
    val bufferSize = 1*1024*1024; // 1M bytes
    val byteBuffer =  ByteBuffer.allocateDirect(bufferSize);
    val serializer =
      new MapSHMShuffleStore.LocalKryoByteBufferBasedSerializer (kryoSerializerSide, byteBuffer);

    //val serializer =
    // new MapSHMShuffleStore.LocalKryoByteBufferBasedSerializer(
    //    threadLocalResourcs.getKryoInstance(), threadLocalResourcs.getByteBuffer())
    //threadLocalResourcs.getKryoInstance().register(RankingsClass.getClass())
    serializer.init();

    serializer.writeClass(classOf[RankingsClass])

    logInfo("I am here!!")

    val numberOfObjects = 20
    val objectList = new ArrayBuffer[RankingsClass]();
    for (i <- 0 to numberOfObjects) {
      objectList += new RankingsClass(i, "hello" + i, i + 1)
    }

    logInfo("total object list size is: " + objectList.length)

    var icount =0;
    objectList.foreach (obj => {
      serializer.writeObject(obj)
      println("type of the object is: " + obj.getClass.getName())
      icount=icount+1
    })

    serializer.flush();

    logInfo("total number of objects serialized is: " + icount)


    val kryoDeserializerSide= ( new KryoSerializer (conf)).newKryo()
    //val kryoDeserializerSide= new Kryo();
    //kryoDeserializerSide.register(classOf[RankingsClass])

    val serializedResultHolder = serializer.getByteBuffer();

    logInfo("serializer has the byte buffer size: " + serializedResultHolder.position());

    val deserializer =
      new ReduceSHMShuffleStore.LocalKryoByteBufferBasedDeserializer(
        kryoDeserializerSide, serializedResultHolder);
    deserializer.init();
    val retrievedClass = deserializer.readClass()

    assert(retrievedClass.equals(classOf[RankingsClass]))

    logInfo("deserializer retrieves the object class definition is: " + retrievedClass.getName())

    var count = 0
    var hasNext = true

    val retrievedObjectList = new ArrayBuffer[RankingsClass]()
    while (hasNext) {
      try {
        val obj = deserializer.readObject()
        if (obj.isInstanceOf[RankingsClass]) {
          val x = obj.asInstanceOf[RankingsClass]
          logInfo("object retrieved is: " + x.pagerank + " " + x.pageurl + " " + x.avgduration)
          retrievedObjectList += obj.asInstanceOf[RankingsClass]
          count = count + 1
        }
      }
      catch {
        case t: Throwable => {
          //NOTE: the deserialization error can be very serve, as you get out of the acutual byte-byffer.
          //in the real C++ shuffle engine. we know we are at the end of the deserialization.
          logError("fails to further retrieve objects. Done. no more objects")
          hasNext = false;
        }
      }
    }

    assert(count == retrievedObjectList.size)

    var p: Int = 0;
    val objectArray = objectList.toArray[RankingsClass]
    retrievedObjectList.foreach(obj => {
      assert(obj.equals(objectArray(p)))
      p = p + 1
    }
    )

  }


  test  ("kryo serialization deserialization test with thread-specific local resources") {
    val conf = new SparkConf(false)

    //to supress a null-pointer from running the test case.
    conf.set("spark.shuffle.manager", "shm")
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    //conf.set("spark.kryo.registrationRequired", "true")
    //conf.registerKryoClasses(Array(classOf[RankingsClass]))
    //conf.set("spark.kryo.registrator", classOf[MyRegistrator].getName)
    //serialization buffer, to be set as the string.
    conf.set("spark.shuffle.shm.serializer.buffer.max.mb", "64");

    sc = new SparkContext("local", "test", conf)

    logInfo("I am done with configuration setting, proceed to actual test logic.")

    val threadLocalResources = getThreadLocalShuffleResource(conf)
    val serializer =
      new MapSHMShuffleStore.LocalKryoByteBufferBasedSerializer (
        threadLocalResources.getKryoInstance, threadLocalResources.getByteBuffer)

    //val serializer =
    // new MapSHMShuffleStore.LocalKryoByteBufferBasedSerializer(
    //    threadLocalResourcs.getKryoInstance(), threadLocalResourcs.getByteBuffer())
    //threadLocalResourcs.getKryoInstance().register(RankingsClass.getClass())
    serializer.init();

    serializer.writeClass(classOf[RankingsClass])

    logInfo("I am here!!")

    val numberOfObjects = 20
    val objectList = new ArrayBuffer[RankingsClass]();
    for (i <- 0 to numberOfObjects) {
      objectList += new RankingsClass(i, "hello" + i, i + 1)
    }

    logInfo("total object list size is: " + objectList.length)

    var icount =0;
    objectList.foreach (obj => {
      serializer.writeObject(obj)
      println("type of the object is: " + obj.getClass.getName())
      icount=icount+1
    })

    serializer.flush();

    logInfo("total number of objects serialized is: " + icount)

    val serializedResultHolder = serializer.getByteBuffer();
    logInfo("serializer has the byte buffer size: " + serializedResultHolder.position());

    val deserializer =
      new ReduceSHMShuffleStore.LocalKryoByteBufferBasedDeserializer(
        threadLocalResources.getKryoInstance, threadLocalResources.getByteBuffer)
    deserializer.init();
    val retrievedClass = deserializer.readClass()

    assert(retrievedClass.equals(classOf[RankingsClass]))

    logInfo("deserializer retrieves the object class definition is: " + retrievedClass.getName())

    var count = 0
    var hasNext = true

    val retrievedObjectList = new ArrayBuffer[RankingsClass]()
    while (hasNext) {
      try {
        val obj = deserializer.readObject()
        if (obj.isInstanceOf[RankingsClass]) {
          val x = obj.asInstanceOf[RankingsClass]
          logInfo("object retrieved is: " + x.pagerank + " " + x.pageurl + " " + x.avgduration)
          retrievedObjectList += obj.asInstanceOf[RankingsClass]
          count = count + 1
        }
      }
      catch {
        case t: Throwable => {
          //NOTE: the deserialization error can be very serve, as you get out of the acutual byte-byffer.
          //in the real C++ shuffle engine. we know we are at the end of the deserialization.
          logError("fails to further retrieve objects. Done. no more objects")
          hasNext = false;
        }
      }
    }

    assert(count == retrievedObjectList.size)

    var p: Int = 0;
    val objectArray = objectList.toArray[RankingsClass]
    retrievedObjectList.foreach(obj => {
      assert(obj.equals(objectArray(p)))
      p = p + 1
    }
    )

  }
}



//NOTE: the following way of defining a class is also correct.
/*
class RankingsClass (pagerankx: Int,
      pageurlx: String,
      avgdurationx: Int)  {

  var pagerank: Int = pagerankx
  var pageurl: String = pageurlx
  var avgduration: Int = avgdurationx

  def equals(other: RankingsClass): Boolean = {
    if (this.pagerank == other.pagerank && this.pageurl == other.pageurl
      && this.avgduration == other.avgduration) {
      true
    }
    else {
      false
    }
  }
}
*/

//NOTE: this is a more concise way to define a simple data class
case class RankingsClass (pagerank: Int,
                          pageurl: String,
                          avgduration: Int)

//NOTE: both case class and register class will have to be at the outer-most class scope. If I
//move these two into the test class private scope. It does not work!!!
class MyRegistrator extends KryoRegistrator with Logging  {

  def registerClasses (k: Kryo) {
    var registered = false
    try {
      k.register(classOf[RankingsClass])
      registered = true
    }
    catch {
      case e: Exception =>
        logError ("fails to register vis MyRegistrator", e)
    }

    if (registered) {
      logInfo("in test suite, successfully register class RankingsClass")
    }
  }
}
