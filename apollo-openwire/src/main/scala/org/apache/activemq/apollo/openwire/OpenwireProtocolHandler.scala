/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.apollo.openwire

import OpenwireConstants._

import org.fusesource.hawtdispatch._
import org.fusesource.hawtbuf._
import collection.mutable.{ListBuffer, HashMap}

import org.apache.activemq.apollo.broker._
import BufferConversions._
import java.io.IOException
import org.apache.activemq.apollo.selector.SelectorParser
import org.apache.activemq.apollo.filter.{BooleanExpression, FilterException}
import org.apache.activemq.apollo.transport._
import org.apache.activemq.apollo.broker.store._
import org.apache.activemq.apollo.util._
import java.util.concurrent.TimeUnit
import java.util.Map.Entry
import protocol._
import scala.util.continuations._
import security.SecurityContext
import support.advisory.AdvisorySupport
import tcp.TcpTransport
import codec.OpenWireFormat
import command._
import org.apache.activemq.apollo.openwire.dto.{OpenwireConnectionStatusDTO,OpenwireDTO}
import org.apache.activemq.apollo.dto.{AcceptingConnectorDTO, TopicDestinationDTO, DurableSubscriptionDestinationDTO, DestinationDTO}
import org.apache.activemq.apollo.openwire.DestinationConverter._

object OpenwireProtocolHandler extends Log {
  def unit:Unit = {}

  val DEFAULT_DIE_DELAY = 5 * 1000L
  var die_delay = DEFAULT_DIE_DELAY

  val preferred_wireformat_settings = new WireFormatInfo();
  preferred_wireformat_settings.setVersion(OpenWireFormat.DEFAULT_VERSION);
  preferred_wireformat_settings.setStackTraceEnabled(true);
  preferred_wireformat_settings.setCacheEnabled(true);
  preferred_wireformat_settings.setTcpNoDelayEnabled(true);
  preferred_wireformat_settings.setTightEncodingEnabled(true);
  preferred_wireformat_settings.setSizePrefixDisabled(false);
  preferred_wireformat_settings.setMaxInactivityDuration(30 * 1000 * 1000);
  preferred_wireformat_settings.setMaxInactivityDurationInitalDelay(10 * 1000 * 1000);
  preferred_wireformat_settings.setCacheSize(1024);
  preferred_wireformat_settings.setMaxFrameSize(OpenWireFormat.DEFAULT_MAX_FRAME_SIZE);
}

/**
 *
 */
class OpenwireProtocolHandler extends ProtocolHandler {

  var minimum_protocol_version = 1

  import OpenwireProtocolHandler._

  def dispatchQueue: DispatchQueue = connection.dispatch_queue

  def protocol = PROTOCOL

  var sink_manager:SinkMux[Command] = null
  var connection_session:Sink[Command] = null
  var closed = false

  var last_command_id=0

  def next_command_id = {
    last_command_id += 1
    last_command_id
  }

  var producerRoutes = new LRUCache[List[DestinationDTO], DeliveryProducerRoute](10) {
    override def onCacheEviction(eldest: Entry[List[DestinationDTO], DeliveryProducerRoute]) = {
      host.router.disconnect(eldest.getKey.toArray, eldest.getValue)
    }
  }

  var host: VirtualHost = null

  private def queue = connection.dispatch_queue

  var session_id: AsciiBuffer = _
  var wire_format: OpenWireFormat = _
  var login: Option[AsciiBuffer] = None
  var passcode: Option[AsciiBuffer] = None
  var dead = false
  val security_context = new SecurityContext
  var config:OpenwireDTO = _

  var heart_beat_monitor: HeartBeatMonitor = new HeartBeatMonitor

  var waiting_on: String = "client request"
  var current_command: Object = _

  var codec:OpenwireCodec = _

  override def create_connection_status = {
    var rc = new OpenwireConnectionStatusDTO
    rc.protocol_version = ""+(if (wire_format == null) 0 else wire_format.getVersion)
    rc.user = login.map(_.toString).getOrElse(null)
    rc.subscription_count = all_consumers.size
    rc.waiting_on = waiting_on
    rc
  }

  override def set_connection(connection: BrokerConnection) = {
    super.set_connection(connection)
    import collection.JavaConversions._

    codec = connection.transport.getProtocolCodec.asInstanceOf[OpenwireCodec]
    var connector_config = connection.connector.config.asInstanceOf[AcceptingConnectorDTO]
    config = connector_config.protocols.find( _.isInstanceOf[OpenwireDTO]).map(_.asInstanceOf[OpenwireDTO]).getOrElse(new OpenwireDTO)

//    protocol_filters = ProtocolFilter.create_filters(config.protocol_filters.toList, this)
//
    import OptionSupport._

//    config.max_data_length.foreach( codec.max_data_length = _ )
//    config.max_header_length.foreach( codec.max_header_length = _ )
//    config.max_headers.foreach( codec.max_headers = _ )

    if( config.destination_separator!=null ||
        config.path_separator!= null ||
        config.any_child_wildcard != null ||
        config.any_descendant_wildcard!= null ) {

//      destination_parser = new DestinationParser().copy(Stomp.destination_parser)
//      if( config.destination_separator!=null ) { destination_parser.destination_separator = config.destination_separator }
//      if( config.path_separator!=null ) { destination_parser.path_separator = config.path_separator }
//      if( config.any_child_wildcard!=null ) { destination_parser.any_child_wildcard = config.any_child_wildcard }
//      if( config.any_descendant_wildcard!=null ) { destination_parser.any_descendant_wildcard = config.any_descendant_wildcard }
    }
  }

  def suspendRead(reason: String) = {
    waiting_on = reason
    connection.transport.suspendRead
  }

  def resumeRead() = {
    waiting_on = "client request"
    connection.transport.resumeRead
  }

  def ack(command: Command):Unit = {
    if (command.isResponseRequired()) {
      val rc = new Response();
      rc.setCorrelationId(command.getCommandId());
      connection_session.offer(rc);
    }
  }

  override def on_transport_failure(error: IOException) = {
    if (!connection.stopped) {
      error.printStackTrace
      suspendRead("shutdown")
      debug(error, "Shutting connection down due to: %s", error)
      connection.stop
    }
  }

  override def on_transport_connected():Unit = {
    security_context.connection_id = Some(connection.id)
    security_context.local_address = connection.transport.getLocalAddress
    security_context.remote_address = connection.transport.getRemoteAddress

    sink_manager = new SinkMux[Command]( connection.transport_sink.map {x=>
      x.setCommandId(next_command_id)
      debug("sending openwire command: %s", x.toString())
      x
    })
    connection_session = new OverflowSink(sink_manager.open());

    // Send our preferred wire format settings..
    connection.transport.offer(preferred_wireformat_settings)

    resumeRead
    reset {
      suspendRead("virtual host lookup")
      this.host = connection.connector.broker.get_default_virtual_host
      resumeRead
      if(host==null) {
        async_die("Could not find default virtual host")
      }
    }
  }

  override def on_transport_disconnected():Unit = {
    if (!closed) {
      closed = true;
      dead = true;

      heart_beat_monitor.stop

      import collection.JavaConversions._
      producerRoutes.foreach{
        case (dests, route) => host.router.disconnect(dests.toArray, route)
      }
      producerRoutes.clear

      //      consumers.foreach{
      //        case (_, consumer) =>
      //          if (consumer.binding == null) {
      //            host.router.unbind(consumer.destination, consumer)
      //          } else {
      //            host.router.get_queue(consumer.binding) {
      //              queue =>
      //                queue.foreach(_.unbind(consumer :: Nil))
      //            }
      //          }
      //      }
      //      consumers = Map()
      trace("openwire protocol resources released")
    }
  }

  override def on_transport_command(command: Object):Unit = {
    if( dead ) {
      // We stop processing client commands once we are dead
      return;
    }
    try {
      current_command = command
      trace("received: %s", command)
      if (wire_format == null) {
        command match {
          case codec: OpenwireCodec =>
            // this is passed on to us by the protocol discriminator
            // so we know which wire format is being used.
          case command: WireFormatInfo =>
            on_wire_format_info(command)
          case _ =>
            die("Unexpected command: " + command.getClass);
        }
      } else {
        command match {
          case msg:ActiveMQMessage=> on_message(msg)
          case ack:MessageAck=> on_message_ack(ack)
          case info:TransactionInfo => on_transaction_info(info)
          case info:ProducerInfo=> on_producer_info(info)
          case info:ConsumerInfo=> on_consumer_info(info)
          case info:SessionInfo=> on_session_info(info)
          case info:ConnectionInfo=> on_connection_info(info)
          case info:RemoveInfo=> on_remove_info(info)
          case info:KeepAliveInfo=> ack(info)
          case info:ShutdownInfo=> ack(info); connection.stop
          case info:FlushCommand=> ack(info)
          case info:DestinationInfo=> on_destination_info(info)

          // case info:ConnectionControl=>
          // case info:ConnectionError=>
          // case info:ConsumerControl=>
          // case info:RemoveSubscriptionInfo=>
          // case info:ControlCommand=>

          ///////////////////////////////////////////////////////////////////
          // Methods for cluster operations
          // These commands are sent to the broker when it's acting like a
          // client to another broker.
          ///////////////////////////////////////////////////////////////////
          // case info:BrokerInfo=>
          // case info:MessageDispatch=>
          // case info:MessageDispatchNotification=>
          // case info:ProducerAck=>


          case _ =>
            die("Unspported command: " + command.getClass);
        }
      }
    } catch {
      case e: Break =>
      case e: Exception =>
        e.printStackTrace
        async_die("Internal Server Error")
    } finally {
      current_command = null
    }
  }

  class ProtocolException(msg:String) extends RuntimeException(msg)
  class Break extends RuntimeException

  def async_fail(msg: String, actual:Command=null):Unit = try {
    fail(msg, actual)
  } catch {
    case x:Break=>
  }

  def fail[T](msg: String, actual:Command=null):T = {
    def respond(command:Command) = {
      if(command.isResponseRequired()) {
        val e = new ProtocolException(msg)
        e.fillInStackTrace

        val rc = new ExceptionResponse()
        rc.setCorrelationId(command.getCommandId())
        rc.setException(e)
        connection_session.offer(rc)
      } else {
        connection_error()
      }
    }
    def connection_error() = {
      val e = new ProtocolException(msg)
      e.fillInStackTrace()

      val err = new ConnectionError()
      err.setException(e)

      connection_session.offer(err)
    }
    (current_command,actual) match {
       case (null, null)=>
         connection_error()
       case (null, command:Command)=>
         respond(command)
       case (command:Command, null)=>
         connection_error()
       case (command:Command, command2:Command)=>
         respond(command)
    }
    throw new Break()
  }

  def async_die(msg: String, actual:Command=null):Unit = try {
    die(msg, actual)
  } catch {
    case x:Break=>
  }

  /**
   * A protocol error that cannot be recovered from. It results in the connections being terminated.
   */
  def die[T](msg: String, actual:Command=null):T = {
    if (!dead) {
      dead = true
      debug("Shutting connection down due to: " + msg)
      // TODO: if there are too many open connections we should just close the connection
      // without waiting for the error to get sent to the client.
      queue.after(die_delay, TimeUnit.MILLISECONDS) {
        connection.stop()
      }
      fail(msg, actual)
    }
    throw new Break()
  }

  def on_wire_format_info(info: WireFormatInfo) = {

    if (!info.isValid()) {
      die("Remote wire format magic is invalid")
    } else if (info.getVersion() < minimum_protocol_version) {
      die("Remote wire format (%s) is lower the minimum version required (%s)".format(info.getVersion(), minimum_protocol_version))
    }

    wire_format = connection.transport.getProtocolCodec.asInstanceOf[OpenwireCodec].format
    wire_format.renegotiateWireFormat(info, preferred_wireformat_settings)

    connection.transport match {
      case x: TcpTransport =>
        x.getSocketChannel.socket.setTcpNoDelay(wire_format.isTcpNoDelayEnabled())
      case _ =>
    }

    val inactive_time = preferred_wireformat_settings.getMaxInactivityDuration().min(info.getMaxInactivityDuration())
    val initial_delay = preferred_wireformat_settings.getMaxInactivityDurationInitalDelay().min(info.getMaxInactivityDurationInitalDelay())

    if (initial_delay != inactive_time) {
      die("We only support an initial delay inactivity duration equal to the max inactivity duration")
    }

    if (inactive_time > 0) {
      heart_beat_monitor.read_interval = inactive_time
      // lets be a little forgiving to account to packet transmission latency.
      heart_beat_monitor.read_interval += inactive_time.min(5000)

      heart_beat_monitor.on_dead = () => {
        async_die("Stale connection.  Missed heartbeat.")
      }

      heart_beat_monitor.write_interval = inactive_time
      heart_beat_monitor.on_keep_alive = () => {
        // we don't care if the offer gets rejected.. since that just
        // means there is other traffic getting transmitted.
        connection.transport.offer(new KeepAliveInfo)
      }
    }

    heart_beat_monitor.transport = connection.transport
    heart_beat_monitor.start

    // Give the client some info about this broker.
    val brokerInfo = new BrokerInfo();
    brokerInfo.setBrokerId(new BrokerId(host.config.id));
    brokerInfo.setBrokerName(host.config.id);
    brokerInfo.setBrokerURL(host.broker.get_connect_address);
    connection_session.offer(brokerInfo);
  }

  ///////////////////////////////////////////////////////////////////
  // Connection / Session / Consumer / Producer state tracking.
  ///////////////////////////////////////////////////////////////////

  def on_connection_info(info: ConnectionInfo) = {
    val id = info.getConnectionId()
    if (!all_connections.contains(id)) {
      new ConnectionContext(info).attach

      security_context.user = info.getUserName
      security_context.password = info.getPassword

      reset {
        if( host.authenticator!=null &&  host.authorizer!=null ) {
          suspendRead("authenticating and authorizing connect")
          if( !host.authenticator.authenticate(security_context) ) {
            async_die("Authentication failed.", info)
            noop
          } else if( !host.authorizer.can_connect_to(security_context, host, connection.connector) ) {
            async_die("Connect not authorized.", info)
            noop
          } else {
            resumeRead
            ack(info);
            noop
          }
        } else {
          ack(info);
          noop
        }
      }
    } else {
      ack(info);
    }
  }

  def on_session_info(info: SessionInfo) = {
    val id = info.getSessionId();
    if (!all_sessions.contains(id)) {
      val parent = all_connections.get(id.getParentId()).getOrElse(die("Cannot add a session to a connection that had not been registered."))
      new SessionContext(parent, info).attach
    }
    ack(info);
  }

  def on_producer_info(info: ProducerInfo) = {
    val id = info.getProducerId
    if (!all_producers.contains(id)) {
      val parent = all_sessions.get(id.getParentId()).getOrElse(die("Cannot add a producer to a session that had not been registered."))
      new ProducerContext(parent, info).attach
    }
    ack(info);
  }

  def on_consumer_info(info: ConsumerInfo) = {
    val id = info.getConsumerId
    if (!all_consumers.contains(id)) {
      val parent = all_sessions.get(id.getParentId()).getOrElse(die("Cannot add a consumer to a session that had not been registered."))
      new ConsumerContext(parent, info).attach
    } else {
      ack(info);
    }
  }

  def on_destination_info(info:DestinationInfo) = {
    val destinations = to_destination_dto(info.getDestination)
    if( info.getDestination.isTemporary ) {
      destinations.foreach(_.temp_owner = connection.id)
    }
    reset{
      val rc = info.getOperationType match {
        case DestinationInfo.ADD_OPERATION_TYPE=>
          host.router.create(destinations, security_context)
        case DestinationInfo.REMOVE_OPERATION_TYPE=>
          host.router.delete(destinations, security_context)
      }
      rc match {
        case None =>
          ack(info)
        case Some(error)=>
          ack(info)
      }
    }
  }

  def on_remove_info(info: RemoveInfo) = {
    info.getObjectId match {
      case id: ConnectionId => all_connections.get(id).foreach(_.dettach)
      case id: SessionId => all_sessions.get(id).foreach(_.dettach)
      case id: ProducerId => all_producers.get(id).foreach(_.dettach)
      case id: ConsumerId => all_consumers.get(id).foreach(_.dettach )
      // case id: DestinationInfo =>
      case _ => die("Invalid object id.")
    }
    ack(info)
  }

  def on_transaction_info(info:TransactionInfo) = {
    val parent = all_connections.get(info.getConnectionId()).getOrElse(die("Cannot add a session to a connection that had not been registered."))
    val id = info.getTransactionId
    info.getType match {
      case TransactionInfo.BEGIN =>
        get_or_create_tx_ctx(parent, id)
        ack(info)

      case TransactionInfo.COMMIT_ONE_PHASE =>
        get_tx_ctx(id).commit {
          ack(info)
        }

      case TransactionInfo.ROLLBACK =>
        get_tx_ctx(id).rollback
        ack(info)

      case TransactionInfo.END =>
        die("XA not yet supported")
      case TransactionInfo.PREPARE =>
        die("XA not yet supported")
      case TransactionInfo.COMMIT_TWO_PHASE =>
        die("XA not yet supported")
      case TransactionInfo.RECOVER =>
        die("XA not yet supported")
      case TransactionInfo.FORGET =>
        die("XA not yet supported")

      case _ =>
        fail("Transaction info type unknown: " + info.getType)

    }
  }

  ///////////////////////////////////////////////////////////////////
  // Core message processing
  ///////////////////////////////////////////////////////////////////

  def on_message(msg: ActiveMQMessage) = {
    val producer = all_producers.get(msg.getProducerId).getOrElse(die("Producer associated with the message has not been registered."))

    if (msg.getOriginalDestination() == null) {
      msg.setOriginalDestination(msg.getDestination());
    }

    if( msg.getTransactionId==null ) {
      perform_send(msg)
    } else {
      get_or_create_tx_ctx(producer.parent.parent, msg.getTransactionId) { (uow)=>
        perform_send(msg, uow)
      }
    }
  }

  def perform_send(msg:ActiveMQMessage, uow:StoreUOW=null): Unit = {

    val destiantion = to_destination_dto(msg.getDestination)
    val key = destiantion.toList
    producerRoutes.get(key) match {
      case null =>
        // create the producer route...

        val route = new DeliveryProducerRoute(host.router) {
          override def connection = Some(OpenwireProtocolHandler.this.connection)
          override def dispatch_queue = queue
          refiller = ^ {
            resumeRead
          }
        }

        // don't process frames until producer is connected...
        connection.transport.suspendRead
        reset {
          val rc = host.router.connect(destiantion, route, security_context)
          rc match {
            case Some(failure) =>
              async_die(failure, msg)
            case None =>
              if (!connection.stopped) {
                resumeRead
                producerRoutes.put(key, route)
                send_via_route(route, msg, uow)
              }
          }
        }

      case route =>
        // we can re-use the existing producer route
        send_via_route(route, msg, uow)

    }
  }

  def send_via_route(route:DeliveryProducerRoute, message:ActiveMQMessage, uow:StoreUOW) = {
    if( !route.targets.isEmpty ) {

      // We may need to add some headers..
      val delivery = new Delivery
      delivery.message = new OpenwireMessage(message)
      delivery.size = message.getSize
      delivery.uow = uow

      if( message.isResponseRequired ) {
        delivery.ack = { (consumed, uow) =>
          dispatchQueue <<| ^{
            ack(message)
          }
        }
      }

      // routes can always accept at least 1 delivery...
      assert( !route.full )
      route.offer(delivery)
      if( route.full ) {
        // but once it gets full.. suspend, so that we get more messages
        // until it's not full anymore.
        suspendRead("blocked destination: "+route.overflowSessions.mkString(", "))
      }

    } else {
      // info("Dropping message.  No consumers interested in message.")
      ack(message)
    }
    //    message.release
  }

  def on_message_ack(info:MessageAck) = {
    val consumer = all_consumers.get(info.getConsumerId).getOrElse(die("Cannot ack a message on a consumer that had not been registered."))
    consumer.ack_handler.credit(info)
    info.getTransactionId match {
      case null =>
        consumer.ack_handler.perform_ack(info)
      case txid =>
        get_or_create_tx_ctx(consumer.parent.parent, txid){ (uow)=>
          consumer.ack_handler.perform_ack(info, uow)
        }
    }
    ack(info)
  }

  //  public Response processAddDestination(DestinationInfo info) throws Exception {
  //      ActiveMQDestination destination = info.getDestination();
  //      if (destination.isTemporary()) {
  //          // Keep track of it so that we can remove them this connection
  //          // shuts down.
  //          temporaryDestinations.add(destination);
  //      }
  //      host.createQueue(destination);
  //      return ack(info);
  //  }

  val all_connections = new HashMap[ConnectionId, ConnectionContext]();
  val all_sessions = new HashMap[SessionId, SessionContext]();
  val all_producers = new HashMap[ProducerId, ProducerContext]();
  val all_consumers = new HashMap[ConsumerId, ConsumerContext]();
  val all_transactions = new HashMap[TransactionId, TransactionContext]();
  val all_temp_dests = List[ActiveMQDestination]();

  class ConnectionContext(val info: ConnectionInfo) {

    val sessions = new HashMap[SessionId, SessionContext]();
    val transactions = new HashMap[TransactionId, TransactionContext]();

    def default_session_id = new SessionId(info.getConnectionId(), -1)

    def attach = {
      // create the default session.
      new SessionContext(this, new SessionInfo(default_session_id)).attach
      all_connections.put(info.getConnectionId, this)
    }

    def dettach = {
      sessions.values.toArray.foreach(_.dettach)
      transactions.values.toArray.foreach(_.dettach)
      all_connections.remove(info.getConnectionId)
    }
  }

  class SessionContext(val parent: ConnectionContext, val info: SessionInfo) {
    val producers = new HashMap[ProducerId, ProducerContext]();
    val consumers = new HashMap[ConsumerId, ConsumerContext]();

    def attach = {
      parent.sessions.put(info.getSessionId, this)
      all_sessions.put(info.getSessionId, this)
    }

    def dettach = {
      producers.values.toArray.foreach(_.dettach)
      consumers.values.toArray.foreach(_.dettach)
      parent.sessions.remove(info.getSessionId)
      all_sessions.remove(info.getSessionId)
    }
  }

  def noop = shift {  k: (Unit=>Unit) => k() }

  class ProducerContext(val parent: SessionContext, val info: ProducerInfo) {
    def attach = {
      parent.producers.put(info.getProducerId, this)
      all_producers.put(info.getProducerId, this)
    }

    def dettach = {
      parent.producers.remove(info.getProducerId)
      all_producers.remove(info.getProducerId)
    }
  }

  class ConsumerContext(val parent: SessionContext, val info: ConsumerInfo) extends BaseRetained with DeliveryConsumer {

//  The following comes in handy if we need to debug the
//  reference counts of the consumers.
//    val r = new BaseRetained
//
//    def setDisposer(p1: Runnable): Unit = r.setDisposer(p1)
//    def retained: Int =r.retained
//
//    def printST(name:String) = {
//      val e = new Exception
//      println(name+": "+connection.map(_.id))
//      println("  "+e.getStackTrace.drop(1).take(4).mkString("\n  "))
//    }
//
//    def retain: Unit = {
//      printST("retain")
//      r.retain
//    }
//    def release: Unit = {
//      printST("release")
//      r.release
//    }

    var selector_expression:BooleanExpression = _
    var destination:Array[DestinationDTO] = _

    val consumer_sink = sink_manager.open()
    val credit_window_filter = new CreditWindowFilter[Delivery](consumer_sink.map { delivery =>
      val dispatch = new MessageDispatch
      dispatch.setConsumerId(info.getConsumerId)
      if( delivery.message eq EndOfBrowseMessage ) {
        // Then send the end of browse message.
        dispatch
      } else {
        var msg = delivery.message.asInstanceOf[OpenwireMessage].message
        ack_handler.track(msg.getMessageId, delivery.ack)
        dispatch.setDestination(msg.getDestination)
        dispatch.setMessage(msg)
      }
      dispatch
    }, Delivery)

    credit_window_filter.credit(0, info.getPrefetchSize)

    val session_manager = new SessionSinkMux[Delivery](credit_window_filter, dispatchQueue, Delivery)

    override def exclusive = info.isExclusive
    override def browser = info.isBrowser

    def attach = {

      if( info.getDestination == null ) fail("destination was not set")
      destination = to_destination_dto(info.getDestination)

      // if they are temp dests.. attach our owner id so that we don't
      // get rejected.
      if( info.getDestination.isTemporary ) {
        destination.foreach(_.temp_owner = connection.get.id)
      }

      parent.consumers.put(info.getConsumerId, this)
      all_consumers.put(info.getConsumerId, this)
      var is_durable_sub = info.getSubscriptionName!=null

      selector_expression = info.getSelector match {
        case null=> null
        case x=>
          try {
            SelectorParser.parse(x)
          } catch {
            case e:FilterException =>
              fail("Invalid selector expression: "+e.getMessage)
          }
      }

      if( is_durable_sub ) {
        destination = destination.map { _ match {
          case x:TopicDestinationDTO=>
            val rc = new DurableSubscriptionDestinationDTO()
            rc.path = x.path
            if( is_durable_sub ) {
              rc.subscription_id = ""
              if( parent.parent.info.getClientId != null ) {
                rc.subscription_id += parent.parent.info.getClientId + ":"
              }
              rc.subscription_id += info.getSubscriptionName
            }
            rc.selector = info.getSelector
            rc
          case _ => die("A durable subscription can only be used on a topic destination")
          }
        }
      }

      reset {
        val rc = host.router.bind(destination, this, security_context)
        rc match {
          case None =>
            ack(info)
            noop
          case Some(reason) =>
            async_fail(reason, info)
            noop
        }
      }
      this.release
    }

    def dettach = {
      host.router.unbind(destination, this, false , security_context)
      parent.consumers.remove(info.getConsumerId)
      all_consumers.remove(info.getConsumerId)
    }

    ///////////////////////////////////////////////////////////////////
    // DeliveryConsumer impl
    ///////////////////////////////////////////////////////////////////

    def dispatch_queue = OpenwireProtocolHandler.this.dispatchQueue

    override def connection = Some(OpenwireProtocolHandler.this.connection)

    def is_persistent = false
    override def receive_buffer_size = codec.write_buffer_size

    def matches(delivery:Delivery) = {
      if( delivery.message.protocol eq OpenwireProtocol ) {
        if( selector_expression!=null ) {
          selector_expression.matches(delivery.message)
        } else {
          true
        }
      } else {
        false
      }
    }

    def connect(p:DeliveryProducer) = new DeliverySession with SinkFilter[Delivery] {
      retain

      def producer = p
      def consumer = ConsumerContext.this
      var closed = false

      val downstream = session_manager.open(producer.dispatch_queue, receive_buffer_size)
      def remaining_capacity = downstream.remaining_capacity

      def close = {
        assert(producer.dispatch_queue.isExecuting)
        if( !closed ) {
          closed = true
          if( browser ) {

            val delivery = new Delivery()
            delivery.message = EndOfBrowseMessage

            if( downstream.full ) {
              // session is full so use an overflow sink so to hold the message,
              // and then trigger closing the session once it empties out.
              val sink = new OverflowSink(downstream)
              sink.refiller = ^{
                dispose
              }
              sink.offer(delivery)
            } else {
              downstream.offer(delivery)
              dispose
            }
          } else {
            dispose
          }
        }
      }

      def dispose = {
        session_manager.close(downstream)
        if( info.getDestination.isTemporary ) {
          reset {
            val rc = host.router.delete(destination, security_context)
            rc match {
              case Some(error) =>
                async_die(error)
              case None =>
                unit
            }
          }
        }
        release
      }

      // Delegate all the flow control stuff to the session
      def offer(delivery:Delivery) = {
        if( full ) {
          false
        } else {
          delivery.message.retain()
          val rc = downstream.offer(delivery)
          assert(rc, "offer should be accepted since it was not full")
          true
        }
      }
    }

    class TrackedAck(val ack:(DeliveryResult, StoreUOW)=>Unit) {
      var credited = false
    }

    val ack_source = createSource(EventAggregators.INTEGER_ADD, dispatch_queue)
    ack_source.setEventHandler(^ {
      val data = ack_source.getData
      credit_window_filter.credit(0, data)
    });
    ack_source.resume

    object ack_handler {

      // TODO: Need to validate all the range ack cases...
      var consumer_acks = ListBuffer[(MessageId,TrackedAck)]()

      def track(msgid:MessageId, ack:(DeliveryResult, StoreUOW)=>Unit) = {
        queue.assertExecuting()
        consumer_acks += msgid -> new TrackedAck(ack)
      }

      def credit(messageAck: MessageAck):Unit = {
        queue.assertExecuting()
        val msgid: MessageId = messageAck.getLastMessageId
        if( messageAck.getAckType == MessageAck.INDIVIDUAL_ACK_TYPE) {
          for( (id, delivery) <- consumer_acks.find(_._1 == msgid) ) {
            if ( !delivery.credited ) {
              ack_source.merge(1)
              delivery.credited = true;
            }
          }
        } else {
          var found = false
          val (acked, not_acked) = consumer_acks.partition{ case (id, ack)=>
            if( id == msgid ) {
              found = true
              true
            } else {
              !found
            }
          }

          for( (id, delivery) <- acked ) {
            // only credit once...
            if( !delivery.credited ) {
              ack_source.merge(1)
              delivery.credited = true;
            }
          }
        }
      }

      def perform_ack(messageAck: MessageAck, uow:StoreUOW=null) = {
        queue.assertExecuting()

        val msgid = messageAck.getLastMessageId
        val consumed = messageAck.getAckType match {
          case MessageAck.DELIVERED_ACK_TYPE => Delivered
          case MessageAck.INDIVIDUAL_ACK_TYPE => Delivered
          case MessageAck.STANDARD_ACK_TYPE => Delivered
          case MessageAck.POSION_ACK_TYPE => Poisoned
          case MessageAck.REDELIVERED_ACK_TYPE => Undelivered
          case MessageAck.UNMATCHED_ACK_TYPE => Delivered
        }

        if( messageAck.getAckType == MessageAck.INDIVIDUAL_ACK_TYPE) {
          consumer_acks = consumer_acks.filterNot{ case (id, delivery)=>
            if( id == msgid) {
              if( delivery.ack!=null ) {
                delivery.ack(consumed, uow)
              }
              true
            } else {
              false
            }
          }
        } else {
          // session acks ack all previously received messages..
          var found = false
          val (acked, not_acked) = consumer_acks.partition{ case (id, ack)=>
            if( id == msgid ) {
              found = true
              true
            } else {
              !found
            }
          }

          if( !found ) {
            trace("%s: ACK failed, invalid message id: %s, dest: %s".format(security_context.remote_address, msgid, destination.mkString(",")))
          } else {
            consumer_acks = not_acked
            acked.foreach{case (id, delivery)=>
              if( delivery.ack!=null ) {
                delivery.ack(consumed, uow)
              }
            }
          }
        }

      }
//
//      def apply(messageAck: MessageAck, uow:StoreUOW=null) = {
//
//        var found = false
//        val (acked, not_acked) = consumer_acks.partition{ case (id, _)=>
//          if( found ) {
//            false
//          } else {
//            if( id == messageAck.getLastMessageId ) {
//              found = true
//            }
//            true
//          }
//        }
//
//        if( acked.isEmpty ) {
//          async_fail("ACK failed, invalid message id: %s".format(messageAck.getLastMessageId), messageAck)
//        } else {
//          consumer_acks = not_acked
//          acked.foreach{case (_, callback)=>
//            if( callback!=null ) {
//              callback(Delivered, uow)
//            }
//          }
//        }
//      }
    }
  }

  class TransactionContext(val parent: ConnectionContext, val id: TransactionId) {

    // TODO: eventually we want to back this /w a broker Queue which
    // can provides persistence and memory swapping.
//    Buffer xid = null;
//    if (tid.isXATransaction()) {
//      xid = XidImpl.toBuffer((Xid) tid);
//    }
//    t = host.getTransactionManager().createTransaction(xid);
//    transactions.put(tid, t);

    val actions = ListBuffer[(StoreUOW)=>Unit]()

    def attach = {
      parent.transactions.put(id, this)
      all_transactions.put(id, this)
    }

    def dettach = {
      actions.clear
      parent.transactions.remove(id)
      all_transactions.remove(id)
    }

    def apply(proc:(StoreUOW)=>Unit) = {
      actions += proc
    }

    def commit(onComplete: => Unit) = {

      val uow = if( host.store!=null ) {
        host.store.create_uow
      } else {
        null
      }

      actions.foreach { proc =>
        proc(uow)
      }

      if( uow!=null ) {
        uow.on_complete(^{
          onComplete
        })
        uow.release
      } else {
        onComplete
      }

    }

    def rollback() = {
      actions.clear
    }

  }

  def create_tx_ctx(connection:ConnectionContext, txid:TransactionId):TransactionContext= {
    if ( all_transactions.contains(txid) ) {
      die("transaction allready started")
    } else {
      val context = new TransactionContext(connection, txid)
      context.attach
      context
    }
  }

  def get_or_create_tx_ctx(connection:ConnectionContext, txid:TransactionId):TransactionContext = {
    all_transactions.get(txid) match {
      case Some(ctx)=> ctx
      case None=>
        val context = new TransactionContext(connection, txid)
        context.attach
        context
    }
  }

  def get_tx_ctx(txid:TransactionId):TransactionContext = {
    all_transactions.get(txid) match {
      case Some(ctx)=> ctx
      case None=> die("transaction not active: %d".format(txid))
    }
  }

  def remove_tx_ctx(txid:TransactionId):TransactionContext= {
    all_transactions.get(txid) match {
      case None=>
        die("transaction not active: %d".format(txid))
      case Some(tx)=>
        tx.dettach
        tx
    }
  }

}
