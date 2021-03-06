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
package net.helenus.test.integration.core.unitofwork;

import static net.helenus.core.Query.eq;


import java.io.Serializable;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.spi.CachingProvider;
import net.bytebuddy.utility.RandomString;
import net.helenus.core.Helenus;
import net.helenus.core.HelenusSession;
import net.helenus.core.UnitOfWork;
import net.helenus.core.annotation.Cacheable;
import net.helenus.core.reflect.Entity;
import net.helenus.mapping.MappingUtil;
import net.helenus.mapping.annotation.Constraints;
import net.helenus.mapping.annotation.Index;
import net.helenus.mapping.annotation.PartitionKey;
import net.helenus.mapping.annotation.Table;
import net.helenus.test.integration.build.AbstractEmbeddedCassandraTest;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import ca.exprofesso.guava.jcache.GuavaCachingProvider;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

@Table
@Cacheable
interface Widget extends Entity, Serializable {
  @PartitionKey
  UUID id();

  @Index
  @Constraints.Distinct
  String name();

  @Constraints.Distinct(value = {"b"})
  String a();

  String b();

  @Constraints.Distinct(alone = false)
  String c();

  @Constraints.Distinct(combined = false)
  String d();
}

public class UnitOfWorkTest extends AbstractEmbeddedCassandraTest {

  static Widget widget;
  static HelenusSession session;

  @BeforeClass
  public static void beforeTest() {
    CachingProvider cachingProvider =
        Caching.getCachingProvider(GuavaCachingProvider.class.getName());
    CacheManager cacheManager = cachingProvider.getCacheManager();
    MutableConfiguration<String, Object> configuration = new MutableConfiguration<>();
    configuration.setStoreByValue(false).setReadThrough(false);
    cacheManager.createCache(
        MappingUtil.getTableName(Widget.class, true).toString(), configuration);

    session =
        Helenus.init(getSession())
            .showCql()
            .add(Widget.class)
            .autoCreateDrop()
            .consistencyLevel(ConsistencyLevel.ONE)
            .idempotentQueryExecution(true)
            .setCacheManager(cacheManager)
            .get();
    widget = session.dsl(Widget.class);
  }

  @Test
  public void testSelectAfterSelect() throws Exception {
    Widget w1, w2, w3, w4;
    UUID key = UUIDs.timeBased();

    // This should inserted Widget, but not cache it.
    w1 =
        session
            .<Widget>insert(widget)
            .value(widget::id, key)
            .value(widget::name, RandomString.make(20))
            .value(widget::a, RandomString.make(10))
            .value(widget::b, RandomString.make(10))
            .value(widget::c, RandomString.make(10))
            .value(widget::d, RandomString.make(10))
            .sync();

    try (UnitOfWork uow = session.begin()) {

      uow.setPurpose("testSelectAfterSelect");

      // This should read from the database and return a Widget.
      w2 =
          session.<Widget>select(widget).where(widget::id, eq(key)).single().sync(uow).orElse(null);

      // This should read from the cache and get the same instance of a Widget.
      w3 =
          session.<Widget>select(widget).where(widget::id, eq(key)).single().sync(uow).orElse(null);

      uow.commit()
          .andThen(
              () -> {
                Assert.assertEquals(w2, w3);
              });
    }

    w4 =
        session
            .<Widget>select(widget)
            .where(widget::name, eq(w1.name()))
            .single()
            .sync()
            .orElse(null);
    Assert.assertEquals(w4, w1);
  }

  @Test
  public void testSelectAfterNestedSelect() throws Exception {
    Widget w1, w1a, w2, w3, w4;
    UUID key1 = UUIDs.timeBased();
    UUID key2 = UUIDs.timeBased();
    String cacheKey1 = MappingUtil.getTableName(Widget.class, false) + "." + key1.toString();
    String cacheKey2 = MappingUtil.getTableName(Widget.class, false) + "." + key2.toString();

    // This should inserted Widget, and not cache it in uow1.
    try (UnitOfWork uow1 = session.begin()) {
      w1 =
          session
              .<Widget>insert(widget)
              .value(widget::id, key1)
              .value(widget::name, RandomString.make(20))
              .value(widget::a, RandomString.make(10))
              .value(widget::b, RandomString.make(10))
              .value(widget::c, RandomString.make(10))
              .value(widget::d, RandomString.make(10))
              .sync(uow1);
      uow1.getCache().put(cacheKey1, w1);
      Assert.assertEquals(w1, uow1.getCache().get(cacheKey1));

      try (UnitOfWork uow2 = session.begin(uow1)) {

        // A "SELECT * FROM widget" query does not contain enough information to fetch an item from cache.
        // This will miss, until we implement a statement cache.
        w1a =
            session
                .<Widget>selectAll(Widget.class)
                .sync(uow2)
                .filter(w -> w.id().equals(key1))
                .findFirst()
                .orElse(null);
        Assert.assertTrue(w1.equals(w1a));

        // This should read from uow1's cache and return the same Widget.
        w2 =
            session
                .<Widget>select(widget)
                .where(widget::id, eq(key1))
                .single()
                .sync(uow2)
                .orElse(null);

        Assert.assertEquals(w1, w2);
        uow2.getCache().put(cacheKey2, w2);

        w3 =
            session
                .<Widget>insert(widget)
                .value(widget::id, key2)
                .value(widget::name, RandomString.make(20))
                .value(widget::a, RandomString.make(10))
                .value(widget::b, RandomString.make(10))
                .value(widget::c, RandomString.make(10))
                .value(widget::d, RandomString.make(10))
                .sync(uow2);

        Assert.assertEquals(w1, uow2.getCache().get(cacheKey1));
        Assert.assertEquals(w2, uow2.getCache().get(cacheKey2));
        uow2.commit()
            .andThen(
                () -> {
                  Assert.assertEquals(w1, w2);
                });
      }

      // This should read from the cache and get the same instance of a Widget.
      w4 =
          session
              .<Widget>select(widget)
              .where(widget::a, eq(w3.a()))
              .and(widget::b, eq(w3.b()))
              .single()
              .sync(uow1)
              .orElse(null);

      uow1.commit()
          .andThen(
              () -> {
                Assert.assertEquals(w3, w4);
              });
    }
  }

  @Test
  public void testSelectViaIndexAfterSelect() throws Exception {
    Widget w1, w2;
    UUID key = UUIDs.timeBased();

    try (UnitOfWork uow = session.begin()) {
      // This should insert and cache Widget in the uow.
      session
          .<Widget>insert(widget)
          .value(widget::id, key)
          .value(widget::name, RandomString.make(20))
          .value(widget::a, RandomString.make(10))
          .value(widget::b, RandomString.make(10))
          .value(widget::c, RandomString.make(10))
          .value(widget::d, RandomString.make(10))
          .sync(uow);

      // This should read from the database and return a Widget.
      w1 =
          session.<Widget>select(widget).where(widget::id, eq(key)).single().sync(uow).orElse(null);

      // This should read from the cache and get the same instance of a Widget.
      w2 =
          session
              .<Widget>select(widget)
              .where(widget::name, eq(w1.name()))
              .single()
              .sync(uow)
              .orElse(null);

      uow.commit()
          .andThen(
              () -> {
                Assert.assertEquals(w1, w2);
              });
    }
  }

  @Test
  public void testSelectAfterUpdated() throws Exception {
    Widget w1, w2, w3, w4, w5, w6;
    UUID key = UUIDs.timeBased();

    // This should inserted Widget, but not cache it.
    w1 =
        session
            .<Widget>insert(widget)
            .value(widget::id, key)
            .value(widget::name, RandomString.make(20))
            .value(widget::a, RandomString.make(10))
            .value(widget::b, RandomString.make(10))
            .value(widget::c, RandomString.make(10))
            .value(widget::d, RandomString.make(10))
            .sync();

    try (UnitOfWork uow = session.begin()) {

      // This should read from the database and return a Widget.
      w2 =
          session.<Widget>select(widget).where(widget::id, eq(key)).single().sync(uow).orElse(null);
      Assert.assertEquals(w1, w2);

      // This should remove the object from the session cache.
      session.<Widget>update(w2).set(widget::name, "Bill").where(widget::id, eq(key)).sync(uow);
      w3 =
          session
              .<Widget>update(w2)
              .set(widget::name, w1.name())
              .where(widget::id, eq(key))
              .sync(uow);

      // Fetch from session cache will cache miss (as it was updated) and trigger a SELECT.
      w4 = session.<Widget>select(widget).where(widget::id, eq(key)).single().sync().orElse(null);
      Assert.assertEquals(w4, w2);
      Assert.assertEquals(w4.name(), w3.name());

      // This should skip the cache.
      w5 =
          session
              .<Widget>select(widget)
              .where(widget::id, eq(key))
              .single()
              .uncached()
              .sync()
              .orElse(null);

      Assert.assertTrue(w5.equals(w2));
      Assert.assertTrue(w2.equals(w5));

      uow.commit()
          .andThen(
              () -> {
                Assert.assertEquals(w2, w3);
              });
    }

    // The name changed, this should miss cache and not find anything in the database.
    w6 =
        session
            .<Widget>select(widget)
            .where(widget::name, eq(w1.name()))
            .single()
            .sync()
            .orElse(null);
    Assert.assertTrue(w2.equals(w5));
  }

  @Test
  public void testSelectAfterDeleted() throws Exception {
    Widget w1, w2, w3, w4;
    UUID key = UUIDs.timeBased();

    // This should inserted Widget, but not cache it.
    w1 =
        session
            .<Widget>insert(widget)
            .value(widget::id, key)
            .value(widget::name, RandomString.make(20))
            .value(widget::a, RandomString.make(10))
            .value(widget::b, RandomString.make(10))
            .value(widget::c, RandomString.make(10))
            .value(widget::d, RandomString.make(10))
            .sync();

    try (UnitOfWork uow = session.begin()) {

      // This should read from the database and return a Widget.
      w2 =
          session.<Widget>select(widget).where(widget::id, eq(key)).single().sync(uow).orElse(null);

      String cacheKey = MappingUtil.getTableName(Widget.class, false) + "." + key.toString();
      uow.getCache().put(cacheKey, w1);

      // This should remove the object from the cache.
      session.delete(widget).where(widget::id, eq(key)).sync(uow);
      uow.getCache().remove(cacheKey);

      // This should fail to read from the cache.
      w3 =
          session.<Widget>select(widget).where(widget::id, eq(key)).single().sync(uow).orElse(null);

      Assert.assertEquals(null, w3);

      uow.commit()
          .andThen(
              () -> {
                Assert.assertEquals(w1, w2);
                Assert.assertEquals(null, w3);
              });
    }

    w4 = session.<Widget>select(widget).where(widget::id, eq(key)).single().sync().orElse(null);

    Assert.assertEquals(null, w4);
  }

  @Test
  public void testBatchingUpdatesAndInserts() throws Exception {
    Widget w1, w2, w3, w4, w5, w6;
    Long committedAt = 0L;
    UUID key = UUIDs.timeBased();
    String cacheKey = MappingUtil.getTableName(Widget.class, false) + "." + key.toString();

    try (UnitOfWork uow = session.begin()) {
      w1 =
          session
              .<Widget>upsert(widget)
              .value(widget::id, key)
              .value(widget::name, RandomString.make(20))
              .value(widget::a, RandomString.make(10))
              .value(widget::b, RandomString.make(10))
              .value(widget::c, RandomString.make(10))
              .value(widget::d, RandomString.make(10))
              .batch(uow);
      Assert.assertTrue(0L == w1.writtenAt(widget::name));
      Assert.assertTrue(0 == w1.ttlOf(widget::name));
      uow.getCache().put(cacheKey, w1);
      w2 =
          session
              .<Widget>update(w1)
              .set(widget::name, RandomString.make(10))
              .where(widget::id, eq(key))
              .usingTtl(30)
              .batch(uow);
      Assert.assertEquals(w1, w2);
      Assert.assertTrue(0L == w2.writtenAt(widget::name));
      Assert.assertTrue(30 == w1.ttlOf(widget::name));
      w3 =
          session
              .<Widget>select(Widget.class)
              .where(widget::id, eq(key))
              .single()
              .sync(uow)
              .orElse(null);
      Assert.assertEquals(w2, w3);
      Assert.assertTrue(0L == w3.writtenAt(widget::name));
      Assert.assertTrue(30 <= w3.ttlOf(widget::name));

      w6 =
          session
              .<Widget>upsert(widget)
              .value(widget::id, UUIDs.timeBased())
              .value(widget::name, RandomString.make(20))
              .value(widget::a, RandomString.make(10))
              .value(widget::b, RandomString.make(10))
              .value(widget::c, RandomString.make(10))
              .value(widget::d, RandomString.make(10))
              .batch(uow);

      uow.getCache().put(cacheKey, w1);
      uow.commit();
      committedAt = uow.committedAt();
      Date d = new Date(committedAt * 1000);
      String date = d.toString();
    }
    // 'c' is distinct, but not on it's own so this should miss cache
    w4 =
        session
            .<Widget>select(Widget.class)
            .where(widget::c, eq(w6.c()))
            .single()
            .sync()
            .orElse(null);
    Assert.assertEquals(w6, w4);
    //TODO(gburd): fix these.
    //long at = w4.writtenAt(widget::name);
    //Assert.assertTrue(at == committedAt);
    //int ttl4 = w4.ttlOf(widget::name);
    //Assert.assertTrue(ttl4 <= 30 && ttl4 > 0);
    w5 =
        session
            .<Widget>select(Widget.class)
            .where(widget::id, eq(w6.id()))
            .uncached()
            .single()
            .sync()
            .orElse(null);
    Assert.assertTrue(w4.equals(w5));
    //Assert.assertTrue(w5.writtenAt(widget::name) == committedAt);
    int ttl5 = w5.ttlOf(widget::name);
    Assert.assertTrue(ttl5 <= 30);
    //Assert.assertTrue(w4.writtenAt(widget::name) == w6.writtenAt(widget::name));
  }

  @Test
  public void testInsertNoOp() throws Exception {
    Widget w1, w2;
    UUID key1 = UUIDs.timeBased();

    try (UnitOfWork uow = session.begin()) {
      // This should inserted Widget, but not cache it.
      w1 =
          session
              .<Widget>insert(widget)
              .value(widget::id, key1)
              .value(widget::name, RandomString.make(20))
              .sync(uow);

      String cacheKey = MappingUtil.getTableName(Widget.class, false) + "." + key1.toString();
      uow.getCache().put(cacheKey, w1);
      /*
      w2 = session.<Widget>upsert(w1)
              .value(widget::a, RandomString.make(10))
              .value(widget::b, RandomString.make(10))
              .value(widget::c, RandomString.make(10))
              .value(widget::d, RandomString.make(10))
              .sync(uow);
      uow.commit();
      */
      uow.abort();
    }
    //Assert.assertEquals(w1, w2);
  }

  @Test
  public void testSelectAfterInsertProperlyCachesEntity() throws Exception {
    Widget w1, w2, w3, w4;
    UUID key = UUIDs.timeBased();

    try (UnitOfWork uow = session.begin()) {
      // This should cache the inserted Widget.
      w1 =
          session
              .<Widget>insert(widget)
              .value(widget::id, key)
              .value(widget::name, RandomString.make(20))
              .value(widget::a, RandomString.make(10))
              .value(widget::b, RandomString.make(10))
              .value(widget::c, RandomString.make(10))
              .value(widget::d, RandomString.make(10))
              .sync(uow);

      String cacheKey = MappingUtil.getTableName(Widget.class, false) + "." + key.toString();
      uow.getCache().put(cacheKey, w1);
      // This should read from the cache and get the same instance of a Widget.
      w2 =
          session.<Widget>select(widget).where(widget::id, eq(key)).single().sync(uow).orElse(null);
      uow.getCache().put(cacheKey, w1);

      uow.commit()
          .andThen(
              () -> {
                Assert.assertEquals(w1, w2);
              });
    }

    // This should read the widget from the session cache and maintain object identity.
    w3 = session.<Widget>select(widget).where(widget::id, eq(key)).single().sync().orElse(null);

    Assert.assertEquals(w1, w3);

    // This should read the widget from the database, no object identity but
    // values should match.
    w4 =
        session
            .<Widget>select(widget)
            .where(widget::id, eq(key))
            .uncached()
            .single()
            .sync()
            .orElse(null);

    Assert.assertFalse(w1 == w4);
    Assert.assertTrue(w1.equals(w4));
    Assert.assertTrue(w4.equals(w1));
  }

  @Test
  public void getAllLoadAllTest() throws Exception {
      String tableName = MappingUtil.getTableName(Widget.class, false).toString();
      UUID uuid1 = UUIDs.random();
      UUID uuid2 = UUIDs.random();
      UUID uuid3 = UUIDs.random();
      String k1 = tableName + "." + uuid1.toString();
      String k2 = tableName + "." + uuid2.toString();
      String k3 = tableName + "." + uuid3.toString();
      Set<String> allKeys = ImmutableSet.<String>of(k1, k2, k3);

      try (UnitOfWork uow1 = session.begin()) {
          Widget w1 = session.<Widget>insert(widget).value(widget::id, uuid1).sync(uow1);
          Widget w2 = session.<Widget>insert(widget).value(widget::id, uuid2).sync(uow1);
          uow1.getCache().put(k1, w1);
          uow1.getCache().put(k2, w2);

          Map<String, Object> results = uow1.getCache().getAll(allKeys);
          Assert.assertEquals(2, results.entrySet().size());
          Assert.assertEquals(results, ImmutableMap.of(k1, w1, k2, w2));

          // getAll tests
          try (UnitOfWork uow2 = session.begin(uow1)) {
              results = uow2.getCache().getAll(allKeys);
              Assert.assertEquals(2, results.entrySet().size());
              Assert.assertEquals(results, ImmutableMap.of(k1, w1, k2, w2));

              Widget w3 = session.<Widget>insert(widget).value(widget::id, uuid3).sync(uow2);
              uow2.getCache().put(k3, w3);
              results = uow2.getCache().getAll(allKeys);
              Assert.assertEquals(3, results.entrySet().size());
              Assert.assertEquals(results, ImmutableMap.of(k1, w1, k2, w2, k3, w3));

              boolean removed = uow2.getCache().remove(k2);
              Assert.assertTrue(removed);
              removed = uow2.getCache().remove(k2);
              Assert.assertFalse(removed);
              results = uow2.getCache().getAll(allKeys);
              Assert.assertEquals(2, results.size());
              Assert.assertEquals(results, ImmutableMap.of(k1, w1, k3, w3));

              // Propagate changes to parent UOW for below tests.
              uow2.commit();
          }

          // loadAll tests
          try (UnitOfWork uow3 = session.begin(uow1)) {
              uow3.getCache().loadAll(allKeys, false, null);
              Assert.assertTrue(uow3.getCache().containsKey(k1));
              Assert.assertTrue(uow3.getCache().containsKey(k3));
              Assert.assertFalse(uow3.getCache().containsKey(k2));
              Assert.assertEquals(w1, uow3.getCache().get(k1));
          }

          try (UnitOfWork uow4 = session.begin(uow1)) {
              UUID uuid3Updated = UUIDs.random();
              Widget w3Updated = session.<Widget>insert(widget).value(widget::id,  uuid3Updated).sync(uow4);

              // Insert a value for a known key, and load the cache without replacing existing values
              uow4.getCache().put(k3, w3Updated);
              Assert.assertEquals(w3Updated,  uow4.getCache().get(k3));
              uow4.getCache().loadAll(allKeys, false, null);
              Assert.assertEquals(w3Updated, uow4.getCache().get(k3));

              // Insert a value for a known key, and load the cache by replacing existing values
              UnitOfWork uow5 = session.begin(uow1);
              uow5.getCache().put(k3, w3Updated);
              Assert.assertEquals(w3Updated,  uow5.getCache().get(k3));
              uow5.getCache().loadAll(allKeys, true, null);
              Assert.assertNotNull(uow5.getCache().get(k3));
              Assert.assertNotEquals(w3Updated,  uow5.getCache().get(k3));
          }
      }
  }

  @Test
  public void getAndPutTest() throws Exception {
      String tableName = MappingUtil.getTableName(Widget.class, false).toString();
      UUID uuid1 = UUIDs.random();
      UUID uuid2 = UUIDs.random();
      String k1 = tableName + "." + uuid1.toString();

      try (UnitOfWork uow1 = session.begin()) {
          Widget w1 = session.<Widget>insert(widget).value(widget::id, uuid1).sync(uow1);
          uow1.getCache().put(k1, w1);
          try (UnitOfWork uow2 = session.begin(uow1)) {
              Widget w2 = session.<Widget>insert(widget).value(widget::id, uuid2).sync(uow2);
              Widget value = (Widget) uow2.getCache().getAndPut(k1, w2);
              Assert.assertEquals(w1, value);
              value = (Widget) uow2.getCache().get(k1);
              Assert.assertEquals(w2, value);
          }
      }
  }

  @Test
  public void removeAllTest() throws Exception {
      String tableName = MappingUtil.getTableName(Widget.class, false).toString();
      UUID uuid1 = UUIDs.random();
      UUID uuid2 = UUIDs.random();
      String k1 = tableName + "." + uuid1.toString();
      String k2 = tableName + "." + uuid2.toString();
      Set<String> keys = ImmutableSet.of(k1, k2, "noValue");

      try (UnitOfWork uow = session.begin()) {
          Widget w1 = session.<Widget>insert(widget).value(widget::id, uuid1).sync(uow);
          Widget w2 = session.<Widget>insert(widget).value(widget::id, uuid2).sync(uow);
          uow.getCache().put(k1, w1);
          uow.getCache().put(k2, w2);
          uow.getCache().removeAll(keys);
      }
  }

  @Test
  public void testDeleteInNestedUOW() throws Exception {
      String tableName = MappingUtil.getTableName(Widget.class, false).toString();
      UUID uuid1 = UUIDs.random();
      UUID uuid2 = UUIDs.random();
      String k1 = tableName + "." + uuid1.toString();
      String k2 = tableName + "." + uuid2.toString();

      try (UnitOfWork uow1 = session.begin()) {
          Widget w1 = session.<Widget>insert(widget).value(widget::id, uuid1)
              .value(widget::name, RandomString.make(10))
              .sync(uow1);
          Widget w2 = session.<Widget>insert(widget).value(widget::id, uuid2)
              .value(widget::name, RandomString.make(20))
              .sync(uow1);
         uow1.getCache().put(k1, w1);
         uow1.getCache().put(k2, w2);

          try (UnitOfWork uow2 = session.begin(uow1)) {
              Object o1 = uow2.getCache().get(k1);
              Object o2 = uow2.getCache().get(k2);
              Assert.assertEquals(w1, o1);
              Assert.assertEquals(w2, o2);

              // k1 should not be available in uow2, but available in uow1.
              uow2.getCache().remove(k1);
              Assert.assertNull(uow2.getCache().get(k1));
              Assert.assertNotNull(uow1.getCache().get(k1));

              // Post-commit, k1 shouldn't be availble in uow1 either
              uow2.commit();
              Assert.assertNull(uow2.getCache().get(k1));
              Assert.assertNull(uow1.getCache().get(k1));

              try (UnitOfWork uow3 = session.begin(uow2)) {
                  uow3.getCache().get(k1);
                  uow3.getCache().get(k2);
                  uow3.getCache().remove(k2);
              }
          }

          uow1.getCache().get(k1);
          uow1.getCache().get(k2);
      }
  }
}
