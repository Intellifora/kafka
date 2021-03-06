/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.kafka

import org.junit.{Before, Test}
import org.junit.Assert._
import ly.stealth.mesos.kafka.Util.Period
import java.util.Date
import scala.collection.JavaConversions._
import ly.stealth.mesos.kafka.Broker.{State, Task, Failover}
import Util.parseMap

class BrokerTest extends MesosTestCase {
  var broker: Broker = null

  @Before
  override def before {
    super.before
    broker = new Broker("0")
    broker.cpus = 0
    broker.mem = 0
  }

  @Test
  def options {
    // $id substitution
    broker.options = parseMap("a=$id,b=2")
    assertEquals(parseMap("a=0,b=2"), broker.options())

    // defaults
    broker.options = parseMap("a=2")
    assertEquals(parseMap("a=2,b=1"), broker.options(parseMap("a=2,b=1")))
  }

  @Test
  def matches {
    // cpus
    broker.cpus = 0.5
    assertNull(broker.matches(offer(cpus = 0.5)))
    assertEquals("cpus 0.49 < 0.5", broker.matches(offer(cpus = 0.49)))
    broker.cpus = 0

    // mem
    broker.mem = 100
    assertNull(broker.matches(offer(mem = 100)))
    assertEquals("mem 99 < 100", broker.matches(offer(mem = 99)))
    broker.mem = 0
  }

  @Test
  def matches_hostname {
    assertNull(broker.matches(offer(hostname = "master")))
    assertNull(broker.matches(offer(hostname = "slave")))

    // token
    broker.constraints = parseMap("hostname=like:master").mapValues(new Constraint(_))
    assertNull(broker.matches(offer(hostname = "master")))
    assertEquals("hostname doesn't match like:master", broker.matches(offer(hostname = "slave")))

    // like
    broker.constraints = parseMap("hostname=like:master.*").mapValues(new Constraint(_))
    assertNull(broker.matches(offer(hostname = "master")))
    assertNull(broker.matches(offer(hostname = "master-2")))
    assertEquals("hostname doesn't match like:master.*", broker.matches(offer(hostname = "slave")))

    // unique
    broker.constraints = parseMap("hostname=unique").mapValues(new Constraint(_))
    assertNull(broker.matches(offer(hostname = "master")))
    assertEquals("hostname doesn't match unique", broker.matches(offer(hostname = "master"), _ => Array("master")))
    assertNull(broker.matches(offer(hostname = "master"), _ => Array("slave")))

    // groupBy
    broker.constraints = parseMap("hostname=groupBy").mapValues(new Constraint(_))
    assertNull(broker.matches(offer(hostname = "master")))
    assertNull(broker.matches(offer(hostname = "master"), _ => Array("master")))
    assertEquals("hostname doesn't match groupBy", broker.matches(offer(hostname = "master"), _ => Array("slave")))
  }

  @Test
  def matches_attributes {
    // like
    broker.constraints = parseMap("rack=like:1-.*").mapValues(new Constraint(_))
    assertNull(broker.matches(offer(attributes = "rack=1-1")))
    assertNull(broker.matches(offer(attributes = "rack=1-2")))
    assertEquals("rack doesn't match like:1-.*", broker.matches(offer(attributes = "rack=2-1")))

    // groupBy
    broker.constraints = parseMap("rack=groupBy").mapValues(new Constraint(_))
    assertNull(broker.matches(offer(attributes = "rack=1")))
    assertNull(broker.matches(offer(attributes = "rack=1"), _ => Array("1")))
    assertEquals("rack doesn't match groupBy", broker.matches(offer(attributes = "rack=2"), _ => Array("1")))
  }

  @Test
  def shouldStart {
    // active
    broker.active = false
    assertFalse(broker.shouldStart())
    broker.active = true
    assertTrue(broker.shouldStart())

    // has task
    broker.task = new Task()
    assertFalse(broker.shouldStart())
    broker.task = null
    assertTrue(broker.shouldStart())

    // failover waiting delay
    val now = new Date(0)
    broker.failover.delay = new Period("1s")
    broker.failover.registerFailure(now)
    assertTrue(broker.failover.isWaitingDelay(now))

    assertFalse(broker.shouldStart(now = now))
    assertTrue(broker.shouldStart(now = new Date(now.getTime + broker.failover.delay.ms)))
    broker.failover.resetFailures()
    assertTrue(broker.shouldStart(now = now))
  }

  @Test
  def shouldStop {
    assertFalse(broker.shouldStop)

    broker.task = new Broker.Task()
    assertTrue(broker.shouldStop)

    broker.active = true
    assertFalse(broker.shouldStop)
  }

  @Test
  def state {
    assertEquals("stopped", broker.state())

    broker.task = new Task(_state = State.STOPPING)
    assertEquals("stopping", broker.state())

    broker.task = null
    broker.active = true
    assertEquals("starting", broker.state())

    broker.task = new Task()
    assertEquals("starting", broker.state())

    broker.task.state = State.RUNNING
    assertEquals("running", broker.state())

    broker.task = null
    broker.failover.delay = new Period("1s")
    broker.failover.registerFailure(new Date(0))
    var state = broker.state(new Date(0))
    assertTrue(state, state.startsWith("failed 1"))

    state = broker.state(new Date(1000))
    assertTrue(state, state.startsWith("starting 2"))
  }

  @Test(timeout = 5000)
  def waitFor {
    def deferStateSwitch(state: String, delay: Long) {
      new Thread() {
        override def run() {
          setName(classOf[BrokerTest].getSimpleName + "-scheduleState")
          Thread.sleep(delay)

          if (state != null) broker.task = new Task(_state = state)
          else broker.task = null
        }
      }.start()
    }

    deferStateSwitch(Broker.State.RUNNING, 100)
    assertTrue(broker.waitFor(State.RUNNING, new Period("200ms")))

    deferStateSwitch(null, 100)
    assertTrue(broker.waitFor(null, new Period("200ms")))

    // timeout
    assertFalse(broker.waitFor(State.RUNNING, new Period("50ms")))
  }

  @Test
  def toJson_fromJson {
    broker.active = true
    broker.cpus = 0.5
    broker.mem = 128
    broker.heap = 128

    broker.constraints = parseMap("a=like:1").mapValues(new Constraint(_))
    broker.options = parseMap("a=1")

    broker.failover.registerFailure(new Date())
    broker.task = new Task("1", "slave", "executor", "host", 9092)

    val read: Broker = new Broker()
    read.fromJson(Util.parseJson("" + broker.toJson))

    BrokerTest.assertBrokerEquals(broker, read)
  }

  // static part
  @Test
  def idFromTaskId {
    assertEquals("0", Broker.idFromTaskId(Broker.nextTaskId(new Broker("0"))))
    assertEquals("100", Broker.idFromTaskId(Broker.nextTaskId(new Broker("100"))))
  }

  // Failover
  @Test
  def Failover_currentDelay {
    val failover = new Failover(new Period("1s"), new Period("5s"))

    failover.failures = 0
    assertEquals(new Period("0s"), failover.currentDelay)

    failover.failures = 1
    assertEquals(new Period("1s"), failover.currentDelay)

    failover.failures = 2
    assertEquals(new Period("2s"), failover.currentDelay)

    failover.failures = 3
    assertEquals(new Period("4s"), failover.currentDelay)

    failover.failures = 4
    assertEquals(new Period("5s"), failover.currentDelay)

    failover.failures = 100
    assertEquals(new Period("5s"), failover.currentDelay)
  }

  @Test
  def Failover_delayExpires {
    val failover = new Failover(new Period("1s"))
    assertEquals(new Date(0), failover.delayExpires)

    failover.registerFailure(new Date(0))
    assertEquals(new Date(1000), failover.delayExpires)

    failover.failureTime = new Date(1000)
    assertEquals(new Date(2000), failover.delayExpires)
  }

  @Test
  def Failover_isWaitingDelay {
    val failover = new Failover(new Period("1s"))
    assertFalse(failover.isWaitingDelay(new Date(0)))

    failover.registerFailure(new Date(0))

    assertTrue(failover.isWaitingDelay(new Date(0)))
    assertTrue(failover.isWaitingDelay(new Date(500)))
    assertTrue(failover.isWaitingDelay(new Date(999)))
    assertFalse(failover.isWaitingDelay(new Date(1000)))
  }

  @Test
  def Failover_isMaxTriesExceeded {
    val failover = new Failover()

    failover.failures = 100
    assertFalse(failover.isMaxTriesExceeded)

    failover.maxTries = 50
    assertTrue(failover.isMaxTriesExceeded)
  }

  @Test
  def Failover_registerFailure_resetFailures {
    val failover = new Failover()
    assertEquals(0, failover.failures)
    assertNull(failover.failureTime)

    failover.registerFailure(new Date(1))
    assertEquals(1, failover.failures)
    assertEquals(new Date(1), failover.failureTime)

    failover.registerFailure(new Date(2))
    assertEquals(2, failover.failures)
    assertEquals(new Date(2), failover.failureTime)

    failover.resetFailures()
    assertEquals(0, failover.failures)
    assertNull(failover.failureTime)
  }

  @Test
  def Failover_toJson_fromJson {
    val failover = new Failover(new Period("1s"), new Period("5s"))
    failover.maxTries = 10
    failover.registerFailure(new Date(0))

    val read: Failover = new Failover()
    read.fromJson(Util.parseJson("" + failover.toJson))

    BrokerTest.assertFailoverEquals(failover, read)
  }

  // Task
  @Test
  def Task_toJson_fromJson {
    val task = new Task("id", "slave", "executor", "host", 9092, parseMap("a=1,b=2"), State.RUNNING)

    val read: Task = new Task()
    read.fromJson(Util.parseJson("" + task.toJson))

    BrokerTest.assertTaskEquals(task, read)
  }
}

object BrokerTest {
  def assertBrokerEquals(expected: Broker, actual: Broker) {
    if (checkNulls(expected, actual)) return

    assertEquals(expected.id, actual.id)
    assertEquals(expected.active, actual.active)

    assertEquals(expected.cpus, actual.cpus, 0.001)
    assertEquals(expected.mem, actual.mem)
    assertEquals(expected.heap, actual.heap)

    assertEquals(expected.constraints, actual.constraints)
    assertEquals(expected.options, actual.options)

    assertFailoverEquals(expected.failover, actual.failover)
    assertTaskEquals(expected.task, actual.task)
  }

  def assertFailoverEquals(expected: Failover, actual: Failover) {
    if (checkNulls(expected, actual)) return

    assertEquals(expected.delay, actual.delay)
    assertEquals(expected.maxDelay, actual.maxDelay)
    assertEquals(expected.maxTries, actual.maxTries)

    assertEquals(expected.failures, actual.failures)
    assertEquals(expected.failureTime, actual.failureTime)
  }

  def assertTaskEquals(expected: Task, actual: Task) {
    if (checkNulls(expected, actual)) return

    assertEquals(expected.id, actual.id)
    assertEquals(expected.executorId, actual.executorId)
    assertEquals(expected.slaveId, actual.slaveId)

    assertEquals(expected.hostname, actual.hostname)
    assertEquals(expected.port, actual.port)
    assertEquals(expected.attributes, actual.attributes)

    assertEquals(expected.state, actual.state)
  }

  private def checkNulls(expected: Object, actual: Object): Boolean = {
    if (expected == actual) return true
    if (expected == null) throw new AssertionError("actual != null")
    if (actual == null) throw new AssertionError("actual == null")
    false
  }
}
