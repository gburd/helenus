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
package net.helenus.core.operation;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Statement;
import java.util.List;
import java.util.stream.Stream;
import net.helenus.core.cache.Facet;

public final class BoundStreamOperation<E>
    extends AbstractStreamOperation<E, BoundStreamOperation<E>> {

  private final BoundStatement boundStatement;
  private final AbstractStreamOperation<E, ?> delegate;

  public BoundStreamOperation(
      BoundStatement boundStatement, AbstractStreamOperation<E, ?> operation) {
    super(operation.sessionOps);
    this.boundStatement = boundStatement;
    this.delegate = operation;
  }

  @Override
  public List<Facet> bindFacetValues() {
    return delegate.bindFacetValues();
  }

  @Override
  public Stream<E> transform(ResultSet resultSet) {
    return delegate.transform(resultSet);
  }

  @Override
  public Statement buildStatement(boolean cached) {
    return boundStatement;
  }

  @Override
  public boolean isSessionCacheable() {
    return delegate.isSessionCacheable();
  }
}
