/*
 * Copyright 2011 TomTom International BV
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
 */

package tomtom.splitter.layer7

import java.util.concurrent.Executors
import com.mongodb.casbah.commons.{MongoDBList, MongoDBObject}
import org.slf4j.LoggerFactory
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpMessage, HttpResponse, CookieDecoder, HttpHeaders, HttpChunk}
import java.util.UUID
import collection.mutable.Set
import com.mongodb.{ServerAddress, BasicDBList, DBObject}
import com.mongodb.casbah.{MongoOptions, MongoConnection}

case class MongoConfig(host: String, port: Int, dbName: String, enableShadowing: Boolean, connsPerHost: Int) {
  def mongoOptions = MongoOptions(connectionsPerHost = connsPerHost)
}

trait MongoDbComponent {
  val sessionId: String
  val mongoConfig: MongoConfig

  import SourceType._, DataType._

  class MongoDb extends DataSinkFactory {

    val log = LoggerFactory.getLogger(getClass)

    val mongoConn = MongoConnection(
      new ServerAddress(mongoConfig.host, mongoConfig.port),
      mongoConfig.mongoOptions)
    val mongoDb = mongoConn(mongoConfig.dbName)
    val requests = mongoDb("requests")

    val executor = Executors.newCachedThreadPool

    case class MongoSink(id: Int) extends DataSink {
      val timestamp = System.currentTimeMillis
      var request: HttpRequest = _
      var shadowRequest: HttpRequest = _
      var response: HttpResponse = _
      var shadowResponse: HttpResponse = _
      var requestChunks: List[HttpChunk] = Nil
      var responseChunks: List[HttpChunk] = Nil
      var shadowRequestChunks: List[HttpChunk] = Nil
      var shadowResponseChunks: List[HttpChunk] = Nil
      var closed = false
      log.trace("Creating new sink {}", this)

      private def finished: Boolean = {
        this synchronized {
          if (mongoConfig.enableShadowing) {
            if (request == null || response == null || shadowResponse == null || shadowRequest == null) {
              false
            } else {
              (!request.isChunked || (requestChunks != Nil && requestChunks.head.isLast)) &&
                (!response.isChunked || (responseChunks != Nil && responseChunks.head.isLast)) &&
                (!shadowRequest.isChunked || (shadowRequestChunks != Nil && shadowRequestChunks.head.isLast)) &&
                (!shadowResponse.isChunked || (shadowResponseChunks != Nil && shadowResponseChunks.head.isLast))
            }
          } else {
            if (request == null || response == null) {
              false
            } else {
              (!request.isChunked || (requestChunks != Nil && requestChunks.head.isLast)) &&
                (!response.isChunked || (responseChunks != Nil && responseChunks.head.isLast))
            }
          }
        }
      }

      override def sinkResponseChunk(sourceType: SourceType.SourceType, chunk: HttpChunk) {
        append(sourceType, Response, chunk)
      }

      override def sinkRequestChunk(sourceType: SourceType.SourceType, chunk: HttpChunk) {
        append(sourceType, Request, chunk)
      }

      override def sinkResponse(sourceType: SourceType.SourceType, message: HttpResponse) {
        append(sourceType, Response, message)
      }

      override def sinkRequest(sourceType: SourceType.SourceType, message: HttpRequest) {
        append(sourceType, Request, message)
      }

      def append(sourceType: SourceType, dataType: DataType, message: HttpMessage) {
        log.trace("Appending to {}: {}", this, (sourceType, dataType, message))
        require(!closed, "Oops, " + this + " is closed")
        this synchronized {
          (sourceType, dataType) match {
            case (Reference, Request) =>
              this synchronized {
                require(request == null)
                request = message.asInstanceOf[HttpRequest]
              }
            case (Reference, Response) =>
              this synchronized {
                require(response == null)
                response = message.asInstanceOf[HttpResponse]
              }
            case (Shadow, Response) =>
              this synchronized {
                require(shadowResponse == null)
                shadowResponse = message.asInstanceOf[HttpResponse]
              }
            case (Shadow, Request) =>
              this synchronized {
                require(shadowRequest == null)
                shadowRequest = message.asInstanceOf[HttpRequest]
              }
            case _ => sys.error("Unknown sourceType/dataType: " + (sourceType, dataType))
          }
          if (finished) {
            close()
          }
        }
      }

      def append(sourceType: SourceType, dataType: DataType, chunk: HttpChunk) {
        (sourceType, dataType) match {
          case (Reference, Request) =>
            this synchronized {
              requestChunks ::= chunk
            }
          case (Reference, Response) =>
            this synchronized {
              responseChunks ::= chunk
            }
          case (Shadow, Response) =>
            this synchronized {
              shadowResponseChunks ::= chunk
            }
          case (Shadow, Request) =>
            this synchronized {
              shadowRequestChunks ::= chunk
            }
        }
      }

      def close() {
        log.trace("closing {}", this)
        this synchronized {
          if (!closed && finished) {
            // ignore if we don't have all the data
            closed = true
            log.trace("Sinking {}", this)
            executor.submit(new Runnable {
              override def run() {
                try {
                  val toSave = MongoDBObject(
                    ("timestamp" -> timestamp) ::
                      ("sessionId" -> sessionId) ::
                      ("requestId" -> id) ::
                      ("request" -> encodeRequest(request, requestChunks)) ::
                      ("response" -> encodeResponse(response, responseChunks)) :: {
                      if (mongoConfig.enableShadowing) {
                        ("shadowRequest" -> encodeRequest(shadowRequest, shadowRequestChunks)) ::
                          ("shadowResponse" -> encodeResponse(shadowResponse, shadowResponseChunks)) ::
                          Nil
                      } else {
                        Nil
                      }
                    })
                  try {
                    log.trace("About to save: {} -> {}", MongoSink.this, toSave)
                    requests.save(toSave)
                  } catch {
                    case e => log.error("Exception saving {}: {}", MongoSink.this,
                      Exceptions.stackTrace(e))
                  }
                } catch {
                  case e => log.error("Exception creating {}: {}", MongoSink.this,
                    Exceptions.stackTrace(e))
                }
              }
            })
          }
        }
      }

      private def encodeRequest(request: HttpRequest, chunks: List[HttpChunk]): DBObject = {
        require(request != null)
        MongoDBObject(
          "method" -> request.getMethod.toString,
          "uri" -> request.getUri.toString,
          "version" -> request.getProtocolVersion.toString,
          "cookies" -> extractCookies(request),
          "headers" -> extractHeaders(request),
          "body" -> request.getContent.toByteBuffer.array,
          "chunks" -> extractChunks(chunks)
        )
      }

      private def encodeResponse(response: HttpResponse, chunks: List[HttpChunk]): DBObject = {
        require(response != null)
        MongoDBObject(
          "status" -> response.getStatus.toString,
          "version" -> response.getProtocolVersion.toString,
          "headers" -> extractHeaders(response),
          "body" -> response.getContent.toByteBuffer.array,
          "chunks" -> extractChunks(chunks))
      }

      private def extractChunks(chunks: List[HttpChunk]): MongoDBList = {
        val chunkList = MongoDBList.newBuilder
        chunks.reverse.foreach {
          chunk: HttpChunk =>
            val content = chunk.getContent
            import content.{array, arrayOffset, readableBytes}
            chunkList += array.drop(arrayOffset).take(readableBytes)
        }
        chunkList.result()
      }

      private def extractHeaders(request: HttpMessage): MongoDBList = {
        import collection.JavaConverters._
        val headers = MongoDBList.newBuilder
        for (headerName <- request.getHeaderNames.asScala) {
          val individualHeaders = request.getHeaders(headerName).asScala
          if (individualHeaders.size == 1) {
            headers += (headerName -> individualHeaders.head)
          } else {
            val values = MongoDBList.newBuilder
            for (value <- individualHeaders) {
              values += value
            }
            headers += (headerName -> values.result)
          }
        }
        headers.result()
      }

      private def extractCookies(request: HttpRequest): MongoDBList = {
        val cookieHeader = request.getHeader(HttpHeaders.Names.COOKIE)
        val cookieObj = MongoDBList.newBuilder
        if (cookieHeader != null) {
          import collection.JavaConverters._
          for (cookie <- new CookieDecoder().decode(cookieHeader).asScala) {
            cookieObj += (cookie.getName -> cookie.getValue)
          }
        }
        cookieObj.result()
      }
    }

    override def dataSink(id: Int) = new ChainingLogSink(Some(MongoSink(id)))
  }

}

/**
 * The database consists of a number of "request" instances
 * (by virtue of the being in the table named "requests")
 * each of which details the outcome of a single inbound request and its
 * responses.
 */

object MongoInspect extends MongoDbComponent {
  val mongoConfig = MongoConfig("localhost", 27017, "splitter", true, 10)
  val sessionId = UUID.randomUUID.toString
  val mongoDb = new MongoDb

  def main(args: Array[String]) {
    val collection = mongoDb.requests
    val seenIds = Set[Int]()
    var dups = 0
    for (obj <- collection) {
      val id = obj.get("requestId").toString.toInt
      if (seenIds.contains(id)) {
        println("Already seen id " + id)
        dups += 1
      } else {
        seenIds += id
      }
    }
    println("Dups: " + dups)
  }
}
