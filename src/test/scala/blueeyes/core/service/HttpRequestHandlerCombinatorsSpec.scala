package blueeyes.core.service

import blueeyes.json.JsonAST._
import blueeyes.json.Printer._
import blueeyes.json.JsonParser
import blueeyes.json.JsonAST.JValue

import org.specs.Specification
import org.specs.matcher.Matchers._

import blueeyes.core.http._
import blueeyes.core.http.MimeType
import blueeyes.core.http.MimeTypes._
import blueeyes.core.http.HttpHeaders._
import blueeyes.json.JsonAST._
import blueeyes.concurrent.Future
import blueeyes.concurrent.Future._
import blueeyes.concurrent.test.FutureMatchers
import blueeyes.util.metrics.DataSize
import DataSize._

import java.net.URLDecoder.{decode => decodeUrl}
import java.net.URLEncoder.{encode => encodeUrl}
import blueeyes.core.data.{ByteMemoryChunk, ByteChunk, BijectionsIdentity, Bijection, GZIPByteChunk}

class HttpRequestHandlerCombinatorsSpec extends Specification with HttpRequestHandlerCombinators with RestPathPatternImplicits with HttpRequestHandlerImplicits with BijectionsIdentity with FutureMatchers {
  implicit val JValueToString = new Bijection[JValue, String] {
    def apply(s: JValue)   = compact(render(s))
    def unapply(t: String) = JsonParser.parse(t)
  }
  implicit val StringToJValue    = JValueToString.inverse

  "composition of paths" should {
    "have the right type" in {
      val handler: HttpRequestHandler[Int] = {
        path("/foo/bar") {
          path("/baz") {
            get { (request: HttpRequest[Int]) =>
              Future.sync(HttpResponse[Int]())
            }
          }
        }
      }

      handler mustBe handler
    }
  }

  "jsonp combinator" should {
    "detect jsonp by callback & method parameters" in {
      val handler: HttpRequestHandler[String] = {
        jsonp {
          path("/") {
            get { request: HttpRequest[JValue] =>
              Future.sync(HttpResponse[JValue](content = Some(JString("foo"))))
            }
          }
        }
      }

      handler {
        HttpRequest[String](
          method = HttpMethods.GET,
          uri    = "/?callback=jsFunc&method=GET"
        )
      } must whenDelivered {
        verify {
          _.content must beSome(
            """jsFunc("foo",{"headers":{},"status":{"code":200,"reason":""}});"""
          )
        }
      }
    }

    "retrieve POST content from query string parameter" in {
      val handler: HttpRequestHandler[String] = {
        jsonp {
          path("/") {
            post { request: HttpRequest[JValue] =>
              Future.sync(HttpResponse[JValue](content = request.content))
            }
          }
        }
      }

      handler {
        HttpRequest[String](
          method  = HttpMethods.GET,
          uri     = "/?callback=jsFunc&method=POST&content=" + encodeUrl("{\"bar\":123}", "UTF-8")
        )
      } must whenDelivered {
        verify {
          _.content must beSome {
            """jsFunc({"bar":123},{"headers":{},"status":{"code":200,"reason":""}});"""
          }
        }
      }
    }

    "retrieve headers from query string parameter" in {
      val handler: HttpRequestHandler[String] = {
        jsonp {
          path("/") {
            get { request: HttpRequest[JValue] =>
              Future.sync(HttpResponse[JValue](content = Some(JString("foo")), headers = request.headers))
            }
          }
        }
      }

      handler {
        HttpRequest[String](
          method = HttpMethods.GET,
          uri    = "/?callback=jsFunc&method=GET&headers=" + encodeUrl("{\"bar\":\"123\"}", "UTF-8")
        )
      } must whenDelivered { 
        verify {
          _.content must beSome {
            """jsFunc("foo",{"headers":{"bar":"123"},"status":{"code":200,"reason":""}});"""
          }
        }
      }
    }

    "pass undefined to callback when there is no content" in {
      val handler: HttpRequestHandler[String] = {
        jsonp {
          path("/") {
            get { request: HttpRequest[JValue] =>
              Future.sync(HttpResponse[JValue]())
            }
          }
        }
      }

      handler {
        HttpRequest[String](
          method = HttpMethods.GET,
          uri    = "/?callback=jsFunc&method=GET&headers=" + encodeUrl("{\"bar\":\"123\"}", "UTF-8")
        )
      } must whenDelivered {
        verify {
          _.content must beSome {
            """jsFunc(undefined,{"headers":{},"status":{"code":200,"reason":""}});"""
          }
        }
      }
    }

    "return headers in 2nd argument to callback function" in {
      val handler: HttpRequestHandler[String] = {
        jsonp {
          path("/") {
            get { request: HttpRequest[JValue] =>
              Future.sync(HttpResponse[JValue](content = Some(JString("foo")), headers = Map("foo" -> "bar")))
            }
          }
        }
      }

      handler {
        HttpRequest[String](
          method = HttpMethods.GET,
          uri    = "/?callback=jsFunc&method=GET"
        )
      } must whenDelivered {
        verify {
          _.content must beSome {
            """jsFunc("foo",{"headers":{"foo":"bar"},"status":{"code":200,"reason":""}});"""
          }
        }
      }
    }
  }

  "cookie combinator" should {
    "propagate default cookie value" in {
      val defaultValue = "defaultValue"
      val f = path("/foo/bar") {
        cookie('someCookie ?: defaultValue) { cookieVal =>
          get { (request: HttpRequest[String]) =>
            Future.sync(HttpResponse[String](content=Some(cookieVal)))
          }
        }
      }(HttpRequest[String](HttpMethods.GET, "/foo/bar"))
      f.value must eventually(beSomething)
      f.value.get.content.get must be(defaultValue)
    }
  }

  "parameter combinator" should {
    "extract parameter" in {
      val f = path("/foo/'bar") {
        parameter[String, String]('bar) { bar =>
          get { (request: HttpRequest[String]) =>
            Future.sync(HttpResponse[String](content=Some(bar)))
          }
        }
      }(HttpRequest[String](HttpMethods.GET, "/foo/blahblah"))
      f.value must eventually(beSomething)
      f.value.get.content must beSome("blahblah")
    }

    "put default parameter value into request parameters field when value not specified" in {
      val handler = path("/foo/") {
        parameter[String, String]('bar ?: "bebe") { bar =>
          get { (request: HttpRequest[String]) =>
            request.parameters mustEqual Map('bar -> "bebe")

            Future.sync(HttpResponse[String](content=Some(bar)))
          }
        }
      }

      handler {
        HttpRequest[String](HttpMethods.GET, "/foo/")
      }
    }

    "extract parameter even when combined with produce" in {
      val f = path("/foo/'bar") {
        produce(application/json) {
          parameter[String, JValue]('bar) { bar =>
            get { (request: HttpRequest[String]) =>
              Future.sync(HttpResponse[JValue](content=Some(JString(bar))))
            }
          }
        }
      }(HttpRequest[String](HttpMethods.GET, "/foo/blahblah"))
      f.value must eventually(beSomething)
      f.value.get.content.map(JString(_)) must beSome(JString(""""blahblah""""))
    }
    "extract decoded parameter" in {
      val f = path("/foo/'bar") {
        produce(application/json) {
          parameter[String, JValue]('bar) { bar =>
            get { (request: HttpRequest[String]) =>
              Future.sync(HttpResponse[JValue](content=Some(JString(bar))))
            }
          }
        }
      }(HttpRequest[String](HttpMethods.GET, "/foo/blah%20blah"))
      f.value must eventually(beSomething)
      f.value.get.content.map(JString(_)) must beSome(JString(""""blah blah""""))
    }
  }

  "path combinator" should {
    "extract symbol" in {
      (path('token) {
        parameter('token) { token =>
          get { (request: HttpRequest[String]) =>
            Future.sync(HttpResponse[String](content=Some(token)))
          }
        }
      }).apply(HttpRequest[String](method = HttpMethods.GET, uri = "A190257C-56F5-499F-A2C6-0FFD0BA7D95B", content = None)).value.get.content must beSome("A190257C-56F5-499F-A2C6-0FFD0BA7D95B")
    }

    "support nested paths" in {
      val f = path("/foo/") {
        path('bar  / "entries") {
          produce(application/json) {
            parameter[String, JValue]('bar) { bar =>
              get { (request: HttpRequest[String]) =>
                Future.sync(HttpResponse[JValue](content=Some(JString(bar))))
              }
            }
          }
        }
      }(HttpRequest[String](HttpMethods.GET, "/foo/blahblah/entries"))
      f.value must eventually(beSomething)
      f.value.get.content.map(JString(_)) must beSome(JString(""""blahblah""""))
    }
  }

  "compress combinator" should {
    "compress content if request contains accept encoding header" in{
      val chunk = new ByteMemoryChunk(Array[Byte]('1', '2'), () => None)
      (compress{
        path("/foo"){
          get { (request: HttpRequest[ByteChunk]) =>
            Future.sync(HttpResponse[ByteChunk](content=request.content))
          }
        }
      }).apply(HttpRequest[ByteChunk](method = HttpMethods.GET, uri = "/foo", content = Some(chunk), headers = HttpHeaders.Empty + `Accept-Encoding`(Encodings.gzip, Encodings.compress))).value.get.content.map(v => new String(v.data)) must beSome(new String(GZIPByteChunk(chunk).data))
    }
    "does not compress content if request does not contain accept appropriate encoding header" in{
      val chunk = new ByteMemoryChunk(Array[Byte]('1', '2'), () => None)
      (compress{
        path("/foo"){
          get { (request: HttpRequest[ByteChunk]) =>
            Future.sync(HttpResponse[ByteChunk](content=request.content))
          }
        }
      }).apply(HttpRequest[ByteChunk](method = HttpMethods.GET, uri = "/foo", content = Some(chunk), headers = HttpHeaders.Empty + `Accept-Encoding`(Encodings.compress))).value.get.content.map(v => new String(v.data)) must beSome("12")
    }
  }
  "aggregate combinator" should {
    "aggregate full content when size is not specified" in{
      (aggregate(None){
        path("/foo"){
          get { (request: HttpRequest[ByteChunk]) =>
            Future.sync(HttpResponse[ByteChunk](content=request.content))
          }
        }
      }).apply(HttpRequest[ByteChunk](method = HttpMethods.GET, uri = "/foo", content = Some(new ByteMemoryChunk(Array[Byte]('1', '2'), () => Some(Future.sync(new ByteMemoryChunk(Array[Byte]('3', '4')))))))).value.get.content.map(v => new String(v.data)) must beSome("1234")
    }
    "aggregate content up to the specified size" in{
      (aggregate(Some(2.bytes)){
        path("/foo"){
          get { (request: HttpRequest[ByteChunk]) =>
            Future.sync(HttpResponse[ByteChunk](content=request.content))
          }
        }
      }).apply(HttpRequest[ByteChunk](method = HttpMethods.GET, uri = "/foo", content = Some(new ByteMemoryChunk(Array[Byte]('1', '2'), () => Some(Future.sync(new ByteMemoryChunk(Array[Byte]('3', '4')))))))).value.get.content.map(v => new String(v.data)) must beSome("12")
    }
  }

  "accept combinator" should{
    "handle request of content is not full in is 'isDefinedAt' method" in{
      (accept(application/json){
        get { (request: HttpRequest[JValue]) =>
          Future.sync(HttpResponse[JValue](content=request.content))
        }
      }).isDefinedAt(HttpRequest[String](method = HttpMethods.GET, uri = "/foo", content = Some("{"), headers = HttpHeaders.Empty + `Content-Type`(application/json))) must be (true)
    }
  }

  "decodeUrl combinator" should{
    "decode request URI" in{
      val f = path("/foo/'bar") {
        produce(application/json) {
          decodeUrl {
            get { (request: HttpRequest[String]) =>
              Future.sync(HttpResponse[JValue](content=Some(JString(request.uri.toString))))
            }
          }
        }
      }(HttpRequest[String](HttpMethods.GET, "/foo/blah%20blah"))
      f.value must eventually(beSomething)
      f.value.get.content.map(JString(_)) must beSome(JString(""""/foo/blah blah""""))
    }
  }
}
