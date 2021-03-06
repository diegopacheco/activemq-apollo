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
package org.apache.activemq.apollo.broker.store.jdbm2.dto;

import org.apache.activemq.apollo.dto.StoreDTO;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.File;

/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
@XmlRootElement(name="jdbm2_store")
@XmlAccessorType(XmlAccessType.FIELD)
public class JDBM2StoreDTO extends StoreDTO {

    @XmlAttribute
    public File directory;

    @XmlAttribute(name="compact_interval")
    public Integer compact_interval;

    @XmlAttribute(name="zero_copy")
    public Boolean zero_copy;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        JDBM2StoreDTO that = (JDBM2StoreDTO) o;

        if (compact_interval != null ? !compact_interval.equals(that.compact_interval) : that.compact_interval != null)
            return false;
        if (directory != null ? !directory.equals(that.directory) : that.directory != null) return false;
        if (zero_copy != null ? !zero_copy.equals(that.zero_copy) : that.zero_copy != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (directory != null ? directory.hashCode() : 0);
        result = 31 * result + (compact_interval != null ? compact_interval.hashCode() : 0);
        result = 31 * result + (zero_copy != null ? zero_copy.hashCode() : 0);
        return result;
    }
}
