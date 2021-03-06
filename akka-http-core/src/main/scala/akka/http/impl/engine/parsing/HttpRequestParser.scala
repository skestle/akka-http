/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import java.lang.{ StringBuilder => JStringBuilder }

import scala.annotation.{ switch, tailrec }
import akka.http.scaladsl.settings.{ ParserSettings, WebSocketSettings }
import akka.util.{ ByteString, OptionVal }
import akka.http.impl.engine.ws.Handshake
import akka.http.impl.model.parser.CharacterClasses
import akka.http.scaladsl.model.{ ParsingException => _, _ }
import headers._
import StatusCodes._
import ParserOutput._
import akka.annotation.InternalApi
import akka.http.impl.util.ByteStringParserInput
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.TLSProtocol.SessionBytes
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

/**
 * INTERNAL API
 */
@InternalApi
private[http] final class HttpRequestParser(
  settings:            ParserSettings,
  websocketSettings:   WebSocketSettings,
  rawRequestUriHeader: Boolean,
  headerParser:        HttpHeaderParser)
  extends GraphStage[FlowShape[SessionBytes, RequestOutput]] { self =>

  import settings._

  val in = Inlet[SessionBytes]("HttpRequestParser.in")
  val out = Outlet[RequestOutput]("HttpRequestParser.out")

  val shape = FlowShape.of(in, out)

  override protected def initialAttributes: Attributes = Attributes.name("HttpRequestParser")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with HttpMessageParser[RequestOutput] with InHandler with OutHandler {

    import HttpMessageParser._

    override val settings = self.settings
    override val headerParser = self.headerParser.createShallowCopy()

    private[this] var method: HttpMethod = _
    private[this] var uri: Uri = _
    private[this] var uriBytes: ByteString = _

    override def onPush(): Unit = handleParserOutput(parseSessionBytes(grab(in)))
    override def onPull(): Unit = handleParserOutput(doPull())

    override def onUpstreamFinish(): Unit =
      if (super.shouldComplete()) completeStage()
      else if (isAvailable(out)) handleParserOutput(doPull())

    setHandlers(in, out, this)

    private def handleParserOutput(output: RequestOutput): Unit = {
      output match {
        case StreamEnd    => completeStage()
        case NeedMoreData => pull(in)
        case x            => push(out, x)
      }
    }

    override def parseMessage(input: ByteString, offset: Int): StateResult =
      if (offset < input.length) {
        var cursor = parseMethod(input, offset)
        cursor = parseRequestTarget(input, cursor)
        cursor = parseProtocol(input, cursor)
        if (byteChar(input, cursor) == '\r' && byteChar(input, cursor + 1) == '\n')
          parseHeaderLines(input, cursor + 2)
        else if (byteChar(input, cursor) == '\n')
          parseHeaderLines(input, cursor + 1)
        else onBadProtocol()
      } else
        // Without HTTP pipelining it's likely that buffer is exhausted after reading one message,
        // so we check above explicitly if we are done and stop work here without running into NotEnoughDataException
        // when continuing to parse.
        continue(startNewMessage)

    def parseMethod(input: ByteString, cursor: Int): Int = {
      @tailrec def parseCustomMethod(ix: Int = 0, sb: JStringBuilder = new JStringBuilder(16)): Int =
        if (ix < maxMethodLength) {
          byteChar(input, cursor + ix) match {
            case ' ' =>
              customMethods(sb.toString) match {
                case Some(m) =>
                  method = m
                  cursor + ix + 1
                case None => throw new ParsingException(NotImplemented, ErrorInfo("Unsupported HTTP method", sb.toString))
              }
            case c => parseCustomMethod(ix + 1, sb.append(c))
          }
        } else
          throw new ParsingException(
            BadRequest,
            ErrorInfo("Unsupported HTTP method", s"HTTP method too long (started with '${sb.toString}'). " +
              "Increase `akka.http.server.parsing.max-method-length` to support HTTP methods with more characters."))

      @tailrec def parseMethod(meth: HttpMethod, ix: Int = 1): Int =
        if (ix == meth.value.length)
          if (byteChar(input, cursor + ix) == ' ') {
            method = meth
            cursor + ix + 1
          } else parseCustomMethod()
        else if (byteChar(input, cursor + ix) == meth.value.charAt(ix)) parseMethod(meth, ix + 1)
        else parseCustomMethod()

      import HttpMethods._
      (byteChar(input, cursor): @switch) match {
        case 'G' => parseMethod(GET)
        case 'P' => byteChar(input, cursor + 1) match {
          case 'O' => parseMethod(POST, 2)
          case 'U' => parseMethod(PUT, 2)
          case 'A' => parseMethod(PATCH, 2)
          case _   => parseCustomMethod()
        }
        case 'D' => parseMethod(DELETE)
        case 'H' => parseMethod(HEAD)
        case 'O' => parseMethod(OPTIONS)
        case 'T' => parseMethod(TRACE)
        case 'C' => parseMethod(CONNECT)
        case 0x16 =>
          throw new ParsingException(
            BadRequest,
            ErrorInfo("Unsupported HTTP method", s"The HTTP method started with 0x16 rather than any known HTTP method. " +
              "Perhaps this was an HTTPS request sent to an HTTP endpoint?"))
        case _ => parseCustomMethod()
      }
    }

    def parseRequestTarget(input: ByteString, cursor: Int): Int = {
      val uriStart = cursor
      val uriEndLimit = cursor + maxUriLength

      @tailrec def findUriEnd(ix: Int = cursor): Int =
        if (ix == input.length) throw NotEnoughDataException
        else if (CharacterClasses.WSPCRLF(input(ix).toChar)) ix
        else if (ix < uriEndLimit) findUriEnd(ix + 1)
        else throw new ParsingException(
          UriTooLong,
          s"URI length exceeds the configured limit of $maxUriLength characters")

      val uriEnd = findUriEnd()
      try {
        uriBytes = input.slice(uriStart, uriEnd)
        uri = Uri.parseHttpRequestTarget(new ByteStringParserInput(uriBytes), mode = uriParsingMode)
      } catch {
        case IllegalUriException(info) => throw new ParsingException(BadRequest, info)
      }
      uriEnd + 1
    }

    override def onBadProtocol(): Nothing = throw new ParsingException(HttpVersionNotSupported)

    // http://tools.ietf.org/html/rfc7230#section-3.3
    override def parseEntity(headers: List[HttpHeader], protocol: HttpProtocol, input: ByteString, bodyStart: Int,
                             clh: Option[`Content-Length`], cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`],
                             expect100continue: Boolean, hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean): StateResult =
      if (hostHeaderPresent || protocol == HttpProtocols.`HTTP/1.0`) {
        def emitRequestStart(
          createEntity: EntityCreator[RequestOutput, RequestEntity],
          headers:      List[HttpHeader]                            = headers) = {
          val allHeaders0 =
            if (rawRequestUriHeader) `Raw-Request-URI`(uriBytes.decodeString(HttpCharsets.`US-ASCII`.nioCharset)) :: headers
            else headers

          val allHeaders =
            if (method == HttpMethods.GET) {
              Handshake.Server.websocketUpgrade(headers, hostHeaderPresent, websocketSettings, headerParser.log) match {
                case OptionVal.Some(upgrade) => upgrade :: allHeaders0
                case OptionVal.None          => allHeaders0
              }
            } else allHeaders0

          emit(RequestStart(method, uri, protocol, allHeaders, createEntity, expect100continue, closeAfterResponseCompletion))
        }

        teh match {
          case None =>
            val contentLength = clh match {
              case Some(`Content-Length`(len)) => len
              case None                        => 0
            }
            if (contentLength == 0) {
              emitRequestStart(emptyEntity(cth))
              setCompletionHandling(HttpMessageParser.CompletionOk)
              startNewMessage(input, bodyStart)
            } else if (!method.isEntityAccepted) {
              failMessageStart(UnprocessableEntity, s"${method.name} requests must not have an entity")
            } else if (contentLength <= input.size - bodyStart) {
              val cl = contentLength.toInt
              emitRequestStart(strictEntity(cth, input, bodyStart, cl))
              setCompletionHandling(HttpMessageParser.CompletionOk)
              startNewMessage(input, bodyStart + cl)
            } else {
              emitRequestStart(defaultEntity(cth, contentLength))
              parseFixedLengthBody(contentLength, closeAfterResponseCompletion)(input, bodyStart)
            }

          case Some(_) if !method.isEntityAccepted =>
            failMessageStart(UnprocessableEntity, s"${method.name} requests must not have an entity")

          case Some(te) =>
            val completedHeaders = addTransferEncodingWithChunkedPeeled(headers, te)
            if (te.isChunked) {
              if (clh.isEmpty) {
                emitRequestStart(chunkedEntity(cth), completedHeaders)
                parseChunk(input, bodyStart, closeAfterResponseCompletion, totalBytesRead = 0L)
              } else failMessageStart("A chunked request must not contain a Content-Length header.")
            } else parseEntity(completedHeaders, protocol, input, bodyStart, clh, cth, teh = None,
              expect100continue, hostHeaderPresent, closeAfterResponseCompletion)
        }
      } else failMessageStart("Request is missing required `Host` header")

  }

  override def toString: String = "HttpRequestParser"
}
