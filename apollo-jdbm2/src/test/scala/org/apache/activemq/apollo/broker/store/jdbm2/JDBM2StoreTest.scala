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
package org.apache.activemq.apollo.broker.store.jdbm2

import dto.JDBM2StoreDTO
import org.apache.activemq.apollo.broker.store.{Store, StoreFunSuiteSupport}
import org.apache.activemq.apollo.util.FileSupport._

/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class JDBM2StoreTest extends StoreFunSuiteSupport {

  def create_store(flushDelay:Long):Store = {
    val rc = new JDBM2Store({
      val rc = new JDBM2StoreDTO
      rc.directory = basedir / "activemq-data"
      rc
    })
    rc.config.flush_delay = flushDelay
    rc
  }

}
