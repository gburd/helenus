/*
 *      Copyright (C) 2015 The Casser Authors
 *      Copyright (C) 2015-2018 The Helenus Authors
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package net.helenus.mapping.convert.udt;

import com.datastax.driver.core.UDTValue;
import java.util.Map;
import java.util.function.Function;
import net.helenus.core.SessionRepository;
import net.helenus.mapping.convert.ProxyValueReader;
import net.helenus.mapping.value.UDTColumnValueProvider;
import net.helenus.support.Transformers;

public final class UDTValueMapToMapConverter implements Function<Object, Object> {

  final ProxyValueReader<UDTValue> reader;

  public UDTValueMapToMapConverter(Class<?> iface, SessionRepository repository) {
    this.reader = new ProxyValueReader<UDTValue>(iface, new UDTColumnValueProvider(repository));
  }

  @Override
  public Object apply(Object t) {
    return Transformers.transformMapValue((Map<Object, UDTValue>) t, reader);
  }
}
