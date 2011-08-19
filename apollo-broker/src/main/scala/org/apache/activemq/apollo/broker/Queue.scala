/**
 *  Licensed to the Apache Software Foundation (ASF) under one or more
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
package org.apache.activemq.apollo.broker

import java.util.concurrent.TimeUnit

import org.fusesource.hawtdispatch._
import protocol.ProtocolFactory
import collection.mutable.ListBuffer
import org.apache.activemq.apollo.broker.store._
import org.apache.activemq.apollo.util._
import org.apache.activemq.apollo.util.list._
import org.fusesource.hawtdispatch.{ListEventAggregator, DispatchQueue, BaseRetained}
import OptionSupport._
import security.SecurityContext
import org.apache.activemq.apollo.dto.{DestinationDTO, QueueDTO}
import java.util.concurrent.atomic.{AtomicReference, AtomicLong, AtomicInteger}
import org.fusesource.hawtbuf.Buffer

object Queue extends Log {
  val subcsription_counter = new AtomicInteger(0)

  val PREFTCH_LOAD_FLAG = 1.toByte
  val PREFTCH_HOLD_FLAG = 2.toByte
}

import Queue._

/**
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class Queue(val router: LocalRouter, val store_id:Long, var binding:QueueBinding, var config:QueueDTO) extends BaseRetained with BindableDeliveryProducer with DeliveryConsumer with BaseService with DomainDestination with Dispatched {

  def id = binding.id

  override def toString = binding.destination.toString

  def virtual_host = router.virtual_host

  var producers = ListBuffer[BindableDeliveryProducer]()
  var inbound_sessions = Set[DeliverySession]()
  var all_subscriptions = Map[DeliveryConsumer, Subscription]()
  var exclusive_subscriptions = ListBuffer[Subscription]()

  def filter = binding.message_filter

  override val dispatch_queue: DispatchQueue = createQueue(id);
  virtual_host.broker.init_dispatch_queue(dispatch_queue)

  def destination_dto: DestinationDTO = binding.binding_dto

  debug("created queue: " + id)

  override def dispose: Unit = {
    ack_source.cancel
  }

  val ack_source = createSource(new ListEventAggregator[(Subscription#AcquiredQueueEntry, DeliveryResult, StoreUOW)](), dispatch_queue)
  ack_source.setEventHandler(^ {drain_acks});
  ack_source.resume

  val session_manager = new SessionSinkMux[Delivery](messages, dispatch_queue, Delivery)

  // sequence numbers.. used to track what's in the store.
  var message_seq_counter = 1L

  val entries = new LinkedNodeList[QueueEntry]()
  val head_entry = new QueueEntry(this, 0L).head
  var tail_entry = new QueueEntry(this, next_message_seq)
  entries.addFirst(head_entry)

  //
  // In-frequently accessed tuning configuration.
  //

  /**
   *  The amount of memory buffer space for the queue..
   */
  def tune_queue_buffer = config.queue_buffer.getOrElse(32*1024)

  //
  // Frequently accessed tuning configuration.
  //

  /**
   * Should this queue persistently store it's entries?
   */
  var tune_persistent = true

  /**
   * Should messages be swapped out of memory if
   * no consumers need the message?
   */
  var tune_swap = true

  /**
   * The number max number of swapped queue entries to load
   * for the store at a time.  Note that swapped entries are just
   * reference pointers to the actual messages.  When not loaded,
   * the batch is referenced as sequence range to conserve memory.
   */
  var tune_swap_range_size = 0

  /**
   *  The amount of memory buffer space to use per subscription.
   */
  var tune_consumer_buffer = 0

  /**
   *  The max memory to allow this queue to grow to.
   */
  var tune_quota = -1L

  def configure(c:QueueDTO) = {
    config = c
    tune_persistent = virtual_host.store !=null && config.persistent.getOrElse(true)
    tune_swap = tune_persistent && config.swap.getOrElse(true)
    tune_swap_range_size = config.swap_range_size.getOrElse(10000)
    tune_consumer_buffer = config.consumer_buffer.getOrElse(256*1024)

    tune_quota = Option(config.quota).map(MemoryPropertyEditor.parse(_)).getOrElse(-1)

    if( tune_persistent ) {
      val record = new QueueRecord
      record.key = store_id
      record.binding_data = binding.binding_data
      record.binding_kind = binding.binding_kind
      virtual_host.store.add_queue(record) { rc => Unit }
    }

    auto_delete_after = config.auto_delete_after.getOrElse(60*5)
    if( auto_delete_after!= 0 ) {
      // we don't auto delete explicitly configured queues,
      // non destination queues, or unified queues.
      if( config.unified.getOrElse(false) || !binding.isInstanceOf[QueueDomainQueueBinding] || !LocalRouter.is_wildcard_config(config) ) {
        auto_delete_after = 0
      }
    }
  }
  dispatch_queue {
    configure(config)
  }

  var now = System.currentTimeMillis

  var enqueue_item_counter = 0L
  var enqueue_size_counter = 0L
  var enqueue_ts = now;

  var dequeue_item_counter = 0L
  var dequeue_size_counter = 0L
  var dequeue_ts = now;

  var nack_item_counter = 0L
  var nack_size_counter = 0L
  var nack_ts = now;

  var expired_item_counter = 0L
  var expired_size_counter = 0L
  var expired_ts = now;

  def queue_size = enqueue_size_counter - dequeue_size_counter
  def queue_items = enqueue_item_counter - dequeue_item_counter

  var swapping_in_size = 0
  var swapping_out_size = 0

  var swapped_in_items = 0
  var swapped_in_size = 0

  var swapped_in_size_max = 0

  var swap_out_item_counter = 0L
  var swap_out_size_counter = 0L

  var swap_in_item_counter = 0L
  var swap_in_size_counter = 0L

  var producer_counter = 0L
  var consumer_counter = 0L

  var individual_swapped_items = 0

  val swap_source = createSource(EventAggregators.INTEGER_ADD, dispatch_queue)
  swap_source.setEventHandler(^{ swap_messages });
  swap_source.resume

  var restored_from_store = false

  var auto_delete_after = 0
  var idled_at = 0L

  def update(on_completed:Runnable) = dispatch_queue {

    val prev_persistent = tune_persistent
    val prev_consumer_size = tune_consumer_buffer
    val prev_queue_buffer = tune_queue_buffer

    configure(binding.config(virtual_host))

    val consumer_buffer_change = tune_consumer_buffer-prev_consumer_size
    if( consumer_buffer_change!=0 ) {
      // for each
      all_subscriptions.values.foreach { sub =>
        // open session
        if( sub.session!=null ) {
          // change the queue capacity, by the change in consumer buffer change.
          addCapacity(consumer_buffer_change)
        }
      }
    }

    swapped_in_size_max += (tune_queue_buffer-prev_queue_buffer)

    restore_from_store {
      check_idle
      trigger_swap
      on_completed.run
    }
  }

  def check_idle {
    if (producers.isEmpty && all_subscriptions.isEmpty && queue_items==0 ) {
      if (idled_at==0) {
        now = System.currentTimeMillis()
        idled_at = now
        if( auto_delete_after!=0 ) {
          dispatch_queue.after(auto_delete_after, TimeUnit.SECONDS) {
            if( now == idled_at ) {
              router._destroy_queue(this)
            }
          }
        }
      }
    } else {
      idled_at = 0
    }
  }

  def restore_from_store(on_completed: => Unit) {
    if (!restored_from_store && tune_persistent) {
      restored_from_store = true
      virtual_host.store.list_queue_entry_ranges(store_id, tune_swap_range_size) { ranges =>
        dispatch_queue {
          if (ranges != null && !ranges.isEmpty) {

            ranges.foreach {
              range =>
                val entry = new QueueEntry(Queue.this, range.first_entry_seq).init(range)
                entries.addLast(entry)

                message_seq_counter = range.last_entry_seq + 1
                enqueue_item_counter += range.count
                enqueue_size_counter += range.size
                tail_entry = new QueueEntry(Queue.this, next_message_seq)
            }

            debug("restored: " + enqueue_item_counter)
          }
          on_completed
        }
      }
    } else {
      on_completed
    }
  }

  protected def _start(on_completed: Runnable) = {
    swapped_in_size_max += tune_queue_buffer;

    restore_from_store {


      // by the time this is run, consumers and producers may have already joined.
      on_completed.run
      schedule_periodic_maintenance
      // wake up the producers to fill us up...
      if (messages.refiller != null) {
        messages.refiller.run
      }

      // kick off dispatching to the consumers.
      check_idle
      trigger_swap
      dispatch_queue << head_entry

    }
  }

  protected def _stop(on_completed: Runnable) = {
    // Disconnect the producers..
    producers.foreach { producer =>
      disconnect(producer)
    }
    // Close all the subscriptions..
    all_subscriptions.values.toArray.foreach { sub:Subscription =>
      sub.close()
    }

    swapped_in_size_max -= tune_queue_buffer;
    trigger_swap

    on_completed.run
  }

  def addCapacity(amount:Int) = {
    val was_full = messages.full
    swapped_in_size_max += amount
    if( was_full && !messages.full ) {
      messages.refiller.run
    }
  }

  object messages extends Sink[Delivery] {

    var refiller: Runnable = null

    def full = (swapped_in_size >= swapped_in_size_max) || !service_state.is_started || (tune_quota >=0 && queue_size > tune_quota)

    def offer(delivery: Delivery): Boolean = {
      if (full) {
        false
      } else {

        // Don't even enqueue if the message has expired.
        val expiration = delivery.message.expiration
        if( expiration != 0 && expiration <= now ) {
          expired(delivery)
          return true
        }

        val entry = tail_entry
        tail_entry = new QueueEntry(Queue.this, next_message_seq)
        val queueDelivery = delivery.copy
        entry.init(queueDelivery)
        
        if( tune_persistent ) {
          queueDelivery.uow = delivery.uow
        }

        entries.addLast(entry)
        enqueue_item_counter += 1
        enqueue_size_counter += entry.size
        enqueue_ts = now;


        // Do we need to do a persistent enqueue???
        if (queueDelivery.uow != null) {
          entry.as_loaded.store
        }

        var dispatched = false
        if( entry.hasSubs ) {
          // try to dispatch it directly...
          entry.dispatch
        }

        val prev = entry.getPrevious

        if( (prev.as_loaded!=null && prev.as_loaded.swapping_out ) || (prev.as_swapped!=null && !prev.as_swapped.swapping_in) ) {
          entry.swap(!entry.as_loaded.acquired)
        } else {
          trigger_swap
        }

        // release the store batch...
        if (queueDelivery.uow != null) {
          queueDelivery.uow.release
          queueDelivery.uow = null
        }

        true
      }
    }
  }

  def expired(delivery:Delivery):Unit = {
    expired_ts = now
    expired_item_counter += 1
    expired_size_counter += delivery.size
  }

  def expired(entry:QueueEntry, dequeue:Boolean=true):Unit = {
    if(dequeue) {
      dequeue_item_counter += 1
      dequeue_size_counter += entry.size
      dequeue_ts = now
      messages.refiller.run
    }

    expired_ts = now
    expired_item_counter += 1
    expired_size_counter += entry.size
  }

  def display_stats: Unit = {
    info("contains: %d messages worth %,.2f MB of data, producers are %s, %d/%d buffer space used.", queue_items, (queue_size.toFloat / (1024 * 1024)), {if (messages.full) "being throttled" else "not being throttled"}, swapped_in_size, swapped_in_size_max)
    info("total messages enqueued %d, dequeues %d ", enqueue_item_counter, dequeue_item_counter)
  }

  def display_active_entries: Unit = {
    var cur = entries.getHead
    var total_items = 0L
    var total_size = 0L
    while (cur != null) {
      if (cur.is_loaded || cur.hasSubs || cur.is_prefetched || cur.is_swapped_range ) {
        info("  => " + cur)
      }

      total_size += cur.size
      if (cur.is_swapped || cur.is_loaded) {
        total_items += 1
      } else if (cur.is_swapped_range ) {
        total_items += cur.as_swapped_range.count
      }
      
      cur = cur.getNext
    }
    info("tail: " + tail_entry)

    // sanitiy checks..
    if(total_items != queue_items) {
      warn("queue_items mismatch, found %d, expected %d", total_size, queue_items)
    }
    if(total_size != queue_size) {
      warn("queue_size mismatch, found %d, expected %d", total_size, queue_size)

    }
  }

  def trigger_swap = {
    if( tune_swap ) {
      swap_source.merge(1)
    }
  }

  def swap_messages = {

    now = System.currentTimeMillis()

    var cur = entries.getHead
    while( cur!=null ) {

      // reset the prefetch flags and handle expiration...
      cur.prefetch_flags = 0
      val next = cur.getNext

      // handle expiration...
      if( cur.expiration != 0 && cur.expiration <= now ) {
        cur.state match {
          case x:QueueEntry#SwappedRange =>
            // load the range to expire the messages in it.
            cur.load
          case x:QueueEntry#Swapped =>
            // remove the expired swapped message.
            expired(cur)
            x.remove
          case x:QueueEntry#Loaded =>
            // remove the expired message if it has not been
            // acquired.
            if( !x.acquired ) {
              expired(cur)
              x.remove
            }
          case _ =>
        }
      }
      cur = next
    }

    // Set the prefetch flags
    all_subscriptions.valuesIterator.foreach( _.refill_prefetch )

    // swap out messages.
    cur = entries.getHead
    while( cur!=null ) {
      val next = cur.getNext
      val loaded = cur.as_loaded
      if( loaded!=null ) {
        if( cur.prefetch_flags==0 && !loaded.acquired  ) {
          val asap = !cur.as_loaded.acquired
          cur.swap(asap)
        } else {
          cur.load // just in case it's getting swapped.
        }
      }
      cur = next
    }


    // Combine swapped items into swapped ranges
    if( individual_swapped_items > tune_swap_range_size*2 ) {

      debug("Looking for swapped entries to combine")

      var distance_from_sub = tune_swap_range_size;
      var cur = entries.getHead
      var combine_counter = 0;

      while( cur!=null ) {

        // get the next now.. since cur may get combined and unlinked
        // from the entry list.
        val next = cur.getNext

        if( cur.prefetch_flags!=0 ) {
          distance_from_sub = 0
        } else {
          distance_from_sub += 1
          if( cur.can_combine_with_prev ) {
            cur.getPrevious.as_swapped_range.combineNext
            combine_counter += 1
          } else {
            if( cur.is_swapped && distance_from_sub > tune_swap_range_size ) {
              cur.swapped_range
              combine_counter += 1
            }
          }

        }
        cur = next
      }
      debug("combined %d entries", combine_counter)
    }

  }

  def schedule_periodic_maintenance:Unit = dispatch_queue.after(1, TimeUnit.SECONDS) {
    if( service_state.is_started ) {
      now = System.currentTimeMillis

      // target tune_min_subscription_rate / sec
      all_subscriptions.foreach{ case (consumer, sub)=>

        if ( sub.tail_parkings < 0 ) {

          // re-calc the avg_advanced_size
          sub.advanced_sizes += sub.advanced_size
          while( sub.advanced_sizes.size > 5 ) {
            sub.advanced_sizes = sub.advanced_sizes.drop(1)
          }
          sub.avg_advanced_size = sub.advanced_sizes.foldLeft(0)(_ + _) /  sub.advanced_sizes.size

        }

        sub.total_advanced_size += sub.advanced_size
        sub.advanced_size = 0
        sub.tail_parkings = 0

      }

      swap_messages
      schedule_periodic_maintenance
    }
  }



  def drain_acks = {
    ack_source.getData.foreach {
      case (entry, consumed, uow) =>
        consumed match {
          case Delivered   =>
            entry.ack(uow)
          case Expired     =>
            entry.entry.queue.expired(entry.entry, false)
            entry.ack(uow)
          case Poisoned    => entry.nack
          case Undelivered => entry.nack
        }
        if( uow!=null ) {
          uow.release()
        }
    }
    messages.refiller.run
  }


  

  /////////////////////////////////////////////////////////////////////
  //
  // Implementation of the DeliveryConsumer trait.  Allows this queue
  // to receive messages from producers.
  //
  /////////////////////////////////////////////////////////////////////

  def matches(delivery: Delivery) = filter.matches(delivery.message)

  def is_persistent = tune_persistent

  def connect(p: DeliveryProducer) = new DeliverySession {
    retain


    override def toString = Queue.this.toString

    override def consumer = Queue.this

    override def producer = p

    val session_max = producer.send_buffer_size
    val session = session_manager.open(producer.dispatch_queue, session_max)

    dispatch_queue {
      inbound_sessions += this
      addCapacity( session_max )
    }

    def remaining_capacity = session.remaining_capacity

    def close = {
      session_manager.close(session)
      dispatch_queue {
        addCapacity( -session_max )
        inbound_sessions -= this
      }
      release
    }

    // Delegate all the flow control stuff to the session
    def full = session.full

    def offer(delivery: Delivery) = {
      if (session.full) {
        false
      } else {
        delivery.message.retain
        if( tune_persistent && delivery.uow!=null ) {
          delivery.uow.retain
        }
        val rc = session.offer(delivery)
        assert(rc, "session should accept since it was not full")
        true
      }
    }

    def refiller = session.refiller

    def refiller_=(value: Runnable) = {session.refiller = value}
  }

  /////////////////////////////////////////////////////////////////////
  //
  // Implementation of the Route trait.  Allows consumers to bind/unbind
  // from this queue so that it can send messages to them.
  //
  /////////////////////////////////////////////////////////////////////

  def connected() = {}

  def bind(value: DeliveryConsumer, security:SecurityContext): Result[Zilch, String] = {
    if(  virtual_host.authorizer!=null && security!=null ) {
      if( value.browser ) {
        if( !virtual_host.authorizer.can_receive_from(security, virtual_host, config) ) {
          return new Failure("Not authorized to browse the queue")
        }
      } else {
        if( !virtual_host.authorizer.can_consume_from(security, virtual_host, config) ) {
          return new Failure("Not authorized to consume from the queue")
        }
      }
    }
    bind(value::Nil)
    Success(Zilch)
  }

  def bind(values: List[DeliveryConsumer]) = {
    values.foreach(_.retain)
    dispatch_queue {
      for (consumer <- values) {
        val sub = new Subscription(this, consumer)
        sub.open
        consumer.release()
      }
    }
  }

  def unbind(values: List[DeliveryConsumer]):Unit = dispatch_queue {
    for (consumer <- values) {
      all_subscriptions.get(consumer) match {
        case Some(subscription) =>
          subscription.close
        case None =>
      }
    }
  }

  def disconnected() = throw new RuntimeException("unsupported")

  def bind(destination:DestinationDTO, consumer: DeliveryConsumer) = {
    bind(consumer::Nil)
  }
  def unbind(consumer: DeliveryConsumer, persistent:Boolean):Unit = {
    unbind(consumer::Nil)
  }

  def connect (destination:DestinationDTO, producer:BindableDeliveryProducer) = {
    import OptionSupport._
    if( config.unified.getOrElse(false) ) {
      // this is a unified queue.. actually have the produce bind to the topic, instead of the
      val topic = router.topic_domain.get_or_create_destination(binding.destination, binding.binding_dto, null).success
      topic.connect(destination, producer)
    } else {
      dispatch_queue {
        producers += producer
        producer_counter += 1
        check_idle
      }
      producer.bind(this::Nil)
    }
  }

  def disconnect (producer:BindableDeliveryProducer) = {
    import OptionSupport._
    if( config.unified.getOrElse(false) ) {
      val topic = router.topic_domain.get_or_create_destination(binding.destination, binding.binding_dto, null).success
      topic.disconnect(producer)
    } else {
      dispatch_queue {
        producers -= producer
        check_idle
      }
      producer.unbind(this::Nil)
    }
  }

  override def connection:Option[BrokerConnection] = None

  override def send_buffer_size = tune_queue_buffer

  /////////////////////////////////////////////////////////////////////
  //
  // Implementation methods.
  //
  /////////////////////////////////////////////////////////////////////


  private def next_message_seq = {
    val rc = message_seq_counter
    message_seq_counter += 1
    rc
  }

  val swap_out_completes_source = createSource(new ListEventAggregator[QueueEntry#Loaded](), dispatch_queue)
  swap_out_completes_source.setEventHandler(^ {drain_swap_out_completes});
  swap_out_completes_source.resume

  def drain_swap_out_completes() = {
    val data = swap_out_completes_source.getData
    data.foreach { loaded =>
      loaded.swapped_out
    }
    messages.refiller.run

  }

  val store_load_source = createSource(new ListEventAggregator[(QueueEntry#Swapped, MessageRecord)](), dispatch_queue)
  store_load_source.setEventHandler(^ {drain_store_loads});
  store_load_source.resume


  def drain_store_loads() = {
    val data = store_load_source.getData
    data.foreach { case (swapped,message_record) =>
      swapped.swapped_in(message_record)
    }

    data.foreach { case (swapped,_) =>
      if( swapped.entry.hasSubs ) {
        swapped.entry.run
      }
    }
  }

}

object QueueEntry extends Sizer[QueueEntry] with Log {
  def size(value: QueueEntry): Int = value.size
}

class QueueEntry(val queue:Queue, val seq:Long) extends LinkedNode[QueueEntry] with Comparable[QueueEntry] with Runnable {
  import QueueEntry._

  // Subscriptions waiting to dispatch this entry.
  var parked:List[Subscription] = Nil

  // subscriptions will set this to non-zero if they are interested
  // in the entry.
  var prefetch_flags:Byte = 0

  // The current state of the entry: Head | Tail | Loaded | Swapped | SwappedRange
  var state:EntryState = new Tail

  def is_prefetched = prefetch_flags == 1

  def <(value:QueueEntry) = this.seq < value.seq
  def <=(value:QueueEntry) = this.seq <= value.seq

  def head():QueueEntry = {
    state = new Head
    this
  }

  def tail():QueueEntry = {
    state = new Tail
    this
  }

  def init(delivery:Delivery):QueueEntry = {
    state = new Loaded(delivery, false)
    queue.swapped_in_size += size
    queue.swapped_in_items += 1
    this
  }

  def init(qer:QueueEntryRecord):QueueEntry = {
    val locator = new AtomicReference[Array[Byte]](Option(qer.message_locator).map(_.toByteArray).getOrElse(null))
    state = new Swapped(qer.message_key, locator, qer.size, qer.expiration)
    this
  }

  def init(range:QueueEntryRange):QueueEntry = {
    state = new SwappedRange(range.last_entry_seq, range.count, range.size, range.expiration)
    this
  }

  def hasSubs = !parked.isEmpty

  /**
   * Dispatches this entry to the consumers and continues dispatching subsequent
   * entries as long as the dispatch results in advancing in their dispatch position.
   */
  def run() = {
    queue.assert_executing
    var cur = this;
    while( cur!=null && cur.isLinked ) {
      val next = cur.getNext
      cur = if( cur.dispatch ) {
        next
      } else {
        null
      }
    }
  }

  def ::=(sub:Subscription) = {
    parked ::= sub
  }

  def :::=(l:List[Subscription]) = {
    parked :::= l
  }


  def -=(s:Subscription) = {
    parked = parked.filterNot(_ == s)
  }

  def nextOrTail():QueueEntry = {
    var entry = getNext
    if (entry == null) {
      entry = queue.tail_entry
    }
    entry
  }


  def compareTo(o: QueueEntry) = {
    (seq - o.seq).toInt
  }

  def toQueueEntryRecord = {
    val qer = new QueueEntryRecord
    qer.queue_key = queue.store_id
    qer.entry_seq = seq
    qer.message_key = state.message_key
    qer.message_locator = Option(state.message_locator).flatMap(x=> Option(x.get)).map(new Buffer(_)).getOrElse(null)
    qer.size = state.size
    qer.expiration = expiration
    qer
  }

  override def toString = {
    "{seq: "+seq+", prefetch_flags: "+prefetch_flags+", value: "+state+", subscriptions: "+parked+"}"
  }

  /////////////////////////////////////////////////////
  //
  // State delegates..
  //
  /////////////////////////////////////////////////////

  // What state is it in?
  def as_head = state.as_head
  def as_tail = state.as_tail

  def as_swapped = state.as_swapped
  def as_swapped_range = state.as_swapped_range
  def as_loaded = state.as_loaded

  def label = state.label

  def is_tail = this == queue.tail_entry
  def is_head = this == queue.head_entry

  def is_loaded = as_loaded!=null
  def is_swapped = as_swapped!=null
  def is_swapped_range = as_swapped_range!=null

  // These should not change the current state.
  def count = state.count
  def size = state.size
  def expiration = state.expiration
  def messageKey = state.message_key
  def is_swapped_or_swapping_out = state.is_swapped_or_swapping_out
  def dispatch() = state.dispatch

  // These methods may cause a change in the current state.
  def swap(asap:Boolean) = state.swap_out(asap)
  def load = state.swap_in
  def remove = state.remove

  def swapped_range = state.swap_range

  def can_combine_with_prev = {
    getPrevious !=null &&
      getPrevious.is_swapped_range &&
        ( is_swapped || is_swapped_range ) &&
          (getPrevious.count + count  < queue.tune_swap_range_size)
  }

  trait EntryState {

    final def entry:QueueEntry = QueueEntry.this

    def as_tail:Tail = null
    def as_loaded:Loaded = null
    def as_swapped:Swapped = null
    def as_swapped_range:SwappedRange = null
    def as_head:Head = null

    /**
     * Gets the size of this entry in bytes.  The head and tail entries always return 0.
     */
    def size = 0

    /**
     * When the entry expires or 0 if it does not expire.
     */
    def expiration = 0L

    /**
     * Gets number of messages that this entry represents
     */
    def count = 0

    /**
     * Retuns a string label used to describe this state.
     */
    def label:String

    /**
     * Gets the message key for the entry.
     * @returns -1 if it is not known.
     */
    def message_key = -1L

    def message_locator: AtomicReference[Array[Byte]] = null

    /**
     * Attempts to dispatch the current entry to the subscriptions position at the entry.
     * @returns true if at least one subscription advanced to the next entry as a result of dispatching.
     */
    def dispatch() = false

    /**
     * @returns true if the entry is either swapped or swapping.
     */
    def is_swapped_or_swapping_out = false

    /**
     * Triggers the entry to get swapped in if it's not already swapped in.
     */
    def swap_in = {}

    /**
     * Triggers the entry to get swapped out if it's not already swapped.
     */
    def swap_out(asap:Boolean) = {}

    def swap_range:Unit = throw new AssertionError("should only be called on swapped entries");

    /**
     * Removes the entry from the queue's linked list of entries.  This gets called
     * as a result of an aquired ack.
     */
    def remove:Unit = {
      // advance subscriptions that were on this entry..
      advance(parked)
      parked = Nil

      // take the entry of the entries list..
      unlink
      //TODO: perhaps refill subscriptions.
    }

    /**
     * Advances the specified subscriptions to the next entry in
     * the linked list
     */
    def advance(advancing: Seq[Subscription]): Unit = {
      val nextPos = nextOrTail
      nextPos :::= advancing.toList
      advancing.foreach(_.advance(nextPos))
      queue.trigger_swap
    }

  }

  /**
   *  Used for the head entry.  This is the starting point for all new subscriptions.
   */
  class Head extends EntryState {

    def label = "head"
    override  def toString = "head"
    override def as_head = this

    /**
     * New subs get parked here at the Head.  There is nothing to actually dispatch
     * in this entry.. just advance the parked subs onto the next entry.
     */
    override def dispatch() = {
      if( parked != Nil ) {
        advance(parked)
        parked = Nil
        true

      } else {
        false
      }
    }

    override def remove = throw new AssertionError("Head entry cannot be removed")
    override def swap_in = throw new AssertionError("Head entry cannot be loaded")
    override def swap_out(asap:Boolean) = throw new AssertionError("Head entry cannot be swapped")
  }

  /**
   * This state is used on the last entry of the queue.  It still has not been initialized
   * with a message, but it may be holding subscriptions.  This state transitions to Loaded
   * once a message is received.
   */
  class Tail extends EntryState {

    def label = "tail"
    override  def toString = "tail"
    override def as_tail:Tail = this

    override def remove = throw new AssertionError("Tail entry cannot be removed")
    override def swap_in = throw new AssertionError("Tail entry cannot be loaded")
    override def swap_out(asap:Boolean) = throw new AssertionError("Tail entry cannot be swapped")

  }

  /**
   * The entry is in this state while a message is loaded in memory.  A message must be in this state
   * before it can be dispatched to a subscription.
   */
  class Loaded(val delivery: Delivery, var stored:Boolean) extends EntryState {

    assert( delivery!=null, "delivery cannot be null")

    var acquired = false
    var swapping_out = false
    var storing = false

    def label = {
      var rc = "loaded"
      if( acquired ) {
        rc += "|aquired"
      }
      if( swapping_out ) {
        rc += "|swapping out"
      }
      rc
    }

    override def toString = { "loaded:{ stored: "+stored+", swapping_out: "+swapping_out+", acquired: "+acquired+", size:"+size+"}" }

    override def count = 1
    override def size = delivery.size
    override def expiration = delivery.message.expiration
    override def message_key = delivery.storeKey
    override def message_locator = delivery.storeLocator

    var remove_pending = false

    override def is_swapped_or_swapping_out = {
      swapping_out
    }

    override  def as_loaded = this

    def store = {
      if(!storing) {
        storing = true
        delivery.uow.enqueue(toQueueEntryRecord)
        delivery.uow.on_flush {
          queue.swap_out_completes_source.merge(this)
        }
      }
    }

    override def swap_out(asap:Boolean) = {
      if( queue.tune_swap && !swapping_out ) {
        swapping_out=true

        queue.swapping_out_size+=size
        if( stored ) {
          swapped_out
        } else {

          // The storeBatch is only set when called from the messages.offer method
          if( delivery.uow!=null ) {
            if( asap ) {
              delivery.uow.complete_asap
            }
          } else {

            // Are we swapping out a non-persistent message?
            if( !storing ) {
              assert( delivery.storeKey == -1 )

              delivery.uow = queue.virtual_host.store.create_uow
              val uow = delivery.uow
              delivery.storeLocator = new AtomicReference[Array[Byte]]()
              delivery.storeKey = uow.store(delivery.createMessageRecord )
              store
              if( asap ) {
                uow.complete_asap
              }
              uow.release
              delivery.uow = null

            } else {
              if( asap ) {
                queue.virtual_host.store.flush_message(message_key) {
                }
              }
            }
          }
        }
      }
    }

    def swapped_out() = {
      assert( state == this )
      storing = false
      stored = true
      delivery.uow = null
      if( swapping_out ) {
        swapping_out = false
        queue.swapping_out_size-=size
        queue.swapped_in_size -= size
        queue.swapped_in_items -= 1

        queue.swap_out_size_counter += size
        queue.swap_out_item_counter += 1

        state = new Swapped(delivery.storeKey, delivery.storeLocator, size, expiration)
        if( can_combine_with_prev ) {
          getPrevious.as_swapped_range.combineNext
        }
        if( remove_pending ) {
          state.remove
        }
      } else {
        if( remove_pending ) {
          delivery.message.release
          queue.swapped_in_size -= size
          queue.swapped_in_items -= 1
          super.remove
        }
      }
    }

    override def swap_in() = {
      if( swapping_out ) {
        swapping_out = false
        queue.swapping_out_size-=size
      }
    }

    override def remove = {
      if( storing | remove_pending ) {
        remove_pending = true
      } else {
        delivery.message.release
        queue.swapped_in_size -= size
        queue.swapped_in_items -= 1
        super.remove
      }
    }

    override def dispatch():Boolean = {

      queue.assert_executing

      if( !acquired && expiration != 0 && expiration <= queue.now ) {
        queue.expired(entry)
        remove
        return true
      }

      // Nothing to dispatch if we don't have subs..
      if( parked.isEmpty ) {
        return false
      }

      var heldBack = ListBuffer[Subscription]()
      var advancing = ListBuffer[Subscription]()

      var acquiringSub: Subscription = null
      parked.foreach{ sub=>

        if( sub.browser ) {
          if (!sub.matches(delivery)) {
            // advance: not interested.
            advancing += sub
          } else {
            if (sub.offer(delivery)) {
              // advance: accepted...
              advancing += sub
            } else {
              // hold back: flow controlled
              heldBack += sub
            }
          }

        } else {
          if( acquired ) {
            // advance: another sub already acquired this entry..
            advancing += sub
          } else {
            if (!sub.matches(delivery)) {
              // advance: not interested.
              advancing += sub
            } else {

              // Find the the first exclusive target of the message
              val exclusive_target = queue.exclusive_subscriptions.find( _.matches(delivery) )

              // Is the current sub not the exclusive target?
              if( exclusive_target.isDefined && (exclusive_target.get != sub) ) {
                // advance: not interested.
                advancing += sub
              } else {
                // Is the sub flow controlled?
                if( sub.full ) {
                  // hold back: flow controlled
                  heldBack += sub
                } else {
                  // advance: accepted...
                  acquiringSub = sub
                  acquired = true

                  val acquiredQueueEntry = sub.acquire(entry)
                  val acquiredDelivery = delivery.copy
                  acquiredDelivery.ack = (consumed, uow)=> {
                    if( uow!=null ) {
                      uow.retain()
                    }
                    queue.ack_source.merge((acquiredQueueEntry, consumed, uow))
                  }

                  val accepted = sub.offer(acquiredDelivery)
                  assert(accepted, "sub should have accepted, it had reported not full earlier.")
                }
              }
            }
          }
        }
      }

      // The acquiring sub is added last to the list so that
      // the other competing subs get first dibs at the next entry.
      if( acquiringSub != null ) {
        advancing += acquiringSub
      }

      if ( advancing.isEmpty ) {
        return false
      } else {

        // The held back subs stay on this entry..
        parked = heldBack.toList

        // the advancing subs move on to the next entry...
        advance(advancing)

//        // swap this entry out if it's not going to be needed soon.
//        if( !hasSubs && prefetch_flags==0 ) {
//          // then swap out to make space...
//          var asap = !acquired
//          flush(asap)
//        }
        queue.trigger_swap
        return true
      }
    }
  }

  /**
   * Loaded entries are moved into the Swapped state reduce memory usage.  Once a Loaded
   * entry is persisted, it can move into this state.  This state only holds onto the
   * the massage key so that it can reload the message from the store quickly when needed.
   */
  class Swapped(override val message_key:Long, override val message_locator:AtomicReference[Array[Byte]], override val size:Int, override val expiration:Long) extends EntryState {

    queue.individual_swapped_items += 1

    var swapping_in = false


    override def count = 1

    override def as_swapped = this

    override def is_swapped_or_swapping_out = true

    def label = {
      var rc = "swapped"
      if( swapping_in ) {
        rc += "|swapping in"
      }
      rc
    }
    override def toString = { "swapped:{ swapping_in: "+swapping_in+", size:"+size+"}" }

    override def swap_in() = {
      if( !swapping_in ) {
//        trace("Start entry load of message seq: %s", seq)
        // start swapping in...
        swapping_in = true
        queue.swapping_in_size += size
        queue.virtual_host.store.load_message(message_key, message_locator) { delivery =>
          // pass off to a source so it can aggregate multiple
          // loads to reduce cross thread synchronization
          if( delivery.isDefined ) {
            queue.store_load_source.merge((this, delivery.get))
          } else {

            info("Detected store dropped message at seq: %d", seq)

            // Looks like someone else removed the message from the store.. lets just
            // tombstone this entry now.
            queue.dispatch_queue {
              remove
            }
          }
        }
      }
    }

    def swapped_in(messageRecord:MessageRecord) = {
      if( swapping_in ) {
//        debug("Loaded message seq: ", seq )
        swapping_in = false
        queue.swapping_in_size -= size

        val delivery = new Delivery()
        delivery.message = ProtocolFactory.get(messageRecord.protocol.toString).get.decode(messageRecord)
        delivery.size = messageRecord.size
        delivery.storeKey = messageRecord.key
        delivery.storeLocator = messageRecord.locator

        queue.swapped_in_size += delivery.size
        queue.swapped_in_items += 1

        queue.swap_in_size_counter += size
        queue.swap_in_item_counter += 1

        queue.individual_swapped_items -= 1
        state = new Loaded(delivery, true)
      } else {
//        debug("Ignoring store load of: ", messageKey)
      }
    }


    override def remove = {
      if( swapping_in ) {
        swapping_in = false
        queue.swapping_in_size -= size
      }
      queue.individual_swapped_items -= 1
      super.remove
    }

    override def swap_range = {
      if( swapping_in ) {
        swapping_in = false
        queue.swapping_in_size -= size
      }
      queue.individual_swapped_items -= 1
      state = new SwappedRange(seq, 1, size, expiration)
    }
  }

  /**
   * A SwappedRange state is assigned entry is used to represent a rage of swapped entries.
   *
   * Even entries that are Swapped can us a significant amount of memory if the queue is holding
   * thousands of them.  Multiple entries in the swapped state can be combined into a single entry in
   * the SwappedRange state thereby conserving even more memory.  A SwappedRange entry only tracks
   * the first, and last sequnce ids of the range.  When the entry needs to be loaded from the range
   * it replaces the swapped range entry with all the swapped entries by querying the store of all the
   * message keys for the entries in the range.
   */
  class SwappedRange(
    /** the last seq id in the range */
    var last:Long,
    /** the number of items in the range */
    var _count:Int,
    /** size in bytes of the range */
    var _size:Int,
    var _expiration:Long) extends EntryState {


    override def count = _count
    override def size = _size
    override def expiration = _expiration

    var swapping_in = false

    override def as_swapped_range = this

    override def is_swapped_or_swapping_out = true

    def label = {
      var rc = "swapped_range"
      if( swapping_in ) {
        rc = "swapped_range|swapping in"
      }
      rc
    }
    override def toString = { "swapped_range:{ swapping_in: "+swapping_in+", count: "+count+", size: "+size+"}" }

    override def swap_in() = {
      if( !swapping_in ) {
        swapping_in = true
        queue.virtual_host.store.list_queue_entries(queue.store_id, seq, last) { records =>
          if( !records.isEmpty ) {
            queue.dispatch_queue {

              var item_count=0
              var size_count=0

              val tmpList = new LinkedNodeList[QueueEntry]()
              records.foreach { record =>
                val entry = new QueueEntry(queue, record.entry_seq).init(record)
                tmpList.addLast(entry)
                item_count += 1
                size_count += record.size
              }

              // we may need to adjust the enqueue count if entries
              // were dropped at the store level
              var item_delta = (count - item_count)
              val size_delta: Int = size - size_count

              if ( item_delta!=0 || size_delta!=0 ) {
                info("Detected store change in range %d to %d. %d message(s) and %d bytes", seq, last, item_delta, size_delta)
                queue.enqueue_item_counter += item_delta
                queue.enqueue_size_counter += size_delta
              }

              linkAfter(tmpList)
              val next = getNext

              // move the subs to the first entry that we just loaded.
              parked.foreach(_.advance(next))
              next :::= parked
              queue.trigger_swap

              unlink

              // TODO: refill prefetches
            }
          } else {
            warn("range load failed")
          }
        }
      }
    }

    /**
     * Combines this queue entry with the next queue entry.
     */
    def combineNext():Unit = {
      val value = getNext
      assert(value!=null)
      assert(value.is_swapped || value.is_swapped_range)
      if( value.is_swapped ) {
        assert(last < value.seq )
        last = value.seq
        _count += 1
      } else if( value.is_swapped_range ) {
        assert(last < value.seq )
        last = value.as_swapped_range.last
        _count += value.as_swapped_range.count
      }
      if(_expiration == 0){
        _expiration = value.expiration
      } else {
        if( value.expiration != 0 ) {
          _expiration = value.expiration.min(_expiration)
        }
      }
      _size += value.size
      value.remove
    }

  }

}

object Subscription extends Log

/**
 * Interfaces a DispatchConsumer with a Queue.  Tracks current position of the consumer
 * on the queue, and the delivery rate so that slow consumers can be detected.  It also
 * tracks the entries which the consumer has acquired.
 *
 */
class Subscription(val queue:Queue, val consumer:DeliveryConsumer) extends DeliveryProducer with Dispatched {
  import Subscription._

  def dispatch_queue = queue.dispatch_queue

  val id = Queue.subcsription_counter.incrementAndGet
  var acquired = new LinkedNodeList[AcquiredQueueEntry]
  var session: DeliverySession = null
  var pos:QueueEntry = null

  var acquired_size = 0L
  def acquired_count = acquired.size()

  var total_advanced_size = 0L
  var advanced_size = 0
  var advanced_sizes = ListBuffer[Int]() // use circular buffer instead.

  var avg_advanced_size = queue.tune_consumer_buffer
  var tail_parkings = 1

  var total_dispatched_count = 0L
  var total_dispatched_size = 0L

  var total_ack_count = 0L
  var total_nack_count = 0L

  override def toString = {
    def seq(entry:QueueEntry) = if(entry==null) null else entry.seq
    "{ id: "+id+", acquired_size: "+acquired_size+", pos: "+seq(pos)+"}"
  }

  def browser = session.consumer.browser
  def exclusive = session.consumer.exclusive

  // This opens up the consumer
  def open() = {
    consumer.retain
    pos = queue.head_entry;
    assert(pos!=null)

    session = consumer.connect(this)
    session.refiller = dispatch_queue.runnable {
      if( pos!=null ) {
        pos.run
      }
    }
    queue.head_entry ::= this

    queue.all_subscriptions += consumer -> this
    queue.consumer_counter += 1
    queue.addCapacity( queue.tune_consumer_buffer )

    if( exclusive ) {
      queue.exclusive_subscriptions.append(this)
    }

    if( queue.service_state.is_started ) {
      // kick off the initial dispatch.
      refill_prefetch
      queue.dispatch_queue << queue.head_entry
    }
    queue.check_idle
  }

  def close() = {
    if(pos!=null) {
      pos -= this
      pos = null

      queue.exclusive_subscriptions = queue.exclusive_subscriptions.filterNot( _ == this )
      queue.all_subscriptions -= consumer
      queue.addCapacity( - queue.tune_consumer_buffer )


      // nack all the acquired entries.
      var next = acquired.getHead
      while( next !=null ) {
        val cur = next;
        next = next.getNext
        cur.nack // this unlinks the entry.
      }

      if( exclusive ) {
        // rewind all the subs to the start of the queue.
        queue.all_subscriptions.values.foreach(_.rewind(queue.head_entry))
      }

      session.refiller = NOOP
      session.close
      session = null
      consumer.release

      queue.check_idle
      queue.trigger_swap
    } else {}
  }

  /**
   * Advances the subscriptions position to the specified
   * queue entry.
   */
  def advance(value:QueueEntry):Unit = {

    assert(value!=null)

    advanced_size += pos.size

    pos = value

    if( tail_parked ) {
      tail_parkings += 0
      if( browser ) {
        close
      }
    }
  }

  /**
   * Rewinds to a previously seen location.. Happens when
   * a nack occurs from another consumer.
   */
  def rewind(value:QueueEntry):Unit = {
    assert(value!=null)
    pos -= this
    value ::= this
    pos = value
    queue.dispatch_queue << value // queue up the entry to get dispatched..
  }

  def tail_parked = pos eq queue.tail_entry

  def matches(entry:Delivery) = session.consumer.matches(entry)
  def full = session.full
  def offer(delivery:Delivery) = {
    if( session.offer(delivery) ) {
      total_dispatched_count += 1
      total_dispatched_size += delivery.size
      true
    } else {
      false
    }
  }

  def acquire(entry:QueueEntry) = new AcquiredQueueEntry(entry)

  def refill_prefetch = {

    var next = if( pos.is_tail ) {
      null // can't prefetch the tail..
    } else if( pos.is_head ) {
      pos.getNext // can't prefetch the head.
    } else {
      pos // start prefetching from the current position.
    }

    var remaining = queue.tune_consumer_buffer - acquired_size
    while( remaining>0 && next!=null ) {
      remaining -= next.size
      next.prefetch_flags = (next.prefetch_flags | PREFTCH_LOAD_FLAG).toByte
      next.load
      next = next.getNext
    }

    remaining = avg_advanced_size
    while( remaining>0 && next!=null ) {
      remaining -= next.size
      next.prefetch_flags = (next.prefetch_flags | PREFTCH_HOLD_FLAG).toByte
      next = next.getNext
    }

  }

  class AcquiredQueueEntry(val entry:QueueEntry) extends LinkedNode[AcquiredQueueEntry] {

    acquired.addLast(this)
    acquired_size += entry.size

    def ack(uow:StoreUOW):Unit = {
      assert_executing
      if(!isLinked) {
        debug("Internal protocol error: message delivery acked/nacked multiple times: "+entry.seq)
        return
      }
      // The session may have already been closed..
      if( session == null ) {
        return;
      }
      total_ack_count += 1
      if (entry.messageKey != -1) {
        val storeBatch = if( uow == null ) {
          queue.virtual_host.store.create_uow
        } else {
          uow
        }
        storeBatch.dequeue(entry.toQueueEntryRecord)
        if( uow == null ) {
          storeBatch.release
        }
      }
      queue.dequeue_item_counter += 1
      queue.dequeue_size_counter += entry.size
      queue.dequeue_ts = queue.now

      // removes this entry from the acquired list.
      unlink()

      // we may now be able to prefetch some messages..
      acquired_size -= entry.size

      val next = entry.nextOrTail
      entry.remove // entry size changes to 0

      queue.trigger_swap
      next.run
    }

    def nack:Unit = {
      assert_executing
      if(!isLinked) {
        warn("Internal protocol error: message delivery acked/nacked multiple times: "+entry.seq)
        return
      }
      // The session may have already been closed..
      if( session == null ) {
        return;
      }

      total_nack_count += 1
      entry.as_loaded.acquired = false
      acquired_size -= entry.size

      // track for stats
      queue.nack_item_counter += 1
      queue.nack_size_counter += entry.size
      queue.nack_ts = queue.now

      // The following does not need to get done for exclusive subs because
      // they end up rewinding all the sub of the head of the queue.
      if( !exclusive ) {

        // rewind all the matching competing subs past the entry.. back to the entry
        queue.all_subscriptions.valuesIterator.foreach{ sub=>
          if( !sub.browser && entry.seq < sub.pos.seq && sub.matches(entry.as_loaded.delivery)) {
            sub.rewind(entry)
          }
        }

      }
      unlink()
    }
  }

}

