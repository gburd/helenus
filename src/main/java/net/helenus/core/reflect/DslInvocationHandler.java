/*
 *      Copyright (C) 2015 The Helenus Authors
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
package net.helenus.core.reflect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.datastax.driver.core.*;

import net.helenus.core.Helenus;
import net.helenus.mapping.HelenusEntity;
import net.helenus.mapping.HelenusMappingEntity;
import net.helenus.mapping.HelenusProperty;
import net.helenus.mapping.type.AbstractDataType;
import net.helenus.mapping.type.DTDataType;
import net.helenus.mapping.type.UDTDataType;
import net.helenus.support.DslPropertyException;
import net.helenus.support.HelenusException;

public class DslInvocationHandler<E> implements InvocationHandler {

	private final HelenusEntity entity;
	private final Optional<HelenusPropertyNode> parent;

	private final Map<Method, HelenusProperty> map = new HashMap<Method, HelenusProperty>();

	private final Map<Method, Object> udtMap = new HashMap<Method, Object>();
	private final Map<Method, Object> tupleMap = new HashMap<Method, Object>();

	public DslInvocationHandler(Class<E> iface, ClassLoader classLoader, Optional<HelenusPropertyNode> parent, Metadata metadata) {

		this.entity = new HelenusMappingEntity(iface, metadata);
		this.parent = parent;

		if (this.entity != null) {
            for (HelenusProperty prop : entity.getOrderedProperties()) {

                map.put(prop.getGetterMethod(), prop);

                AbstractDataType type = prop.getDataType();
                Class<?> javaType = prop.getJavaType();

                if (type instanceof UDTDataType && !UDTValue.class.isAssignableFrom(javaType)) {

                    Object childDsl = Helenus.dsl(javaType, classLoader,
                            Optional.of(new HelenusPropertyNode(prop, parent)), metadata);

                    udtMap.put(prop.getGetterMethod(), childDsl);
                }

                if (type instanceof DTDataType) {
                    DTDataType dataType = (DTDataType) type;

                    if (dataType.getDataType() instanceof TupleType && !TupleValue.class.isAssignableFrom(javaType)) {

                        Object childDsl = Helenus.dsl(javaType, classLoader,
                                Optional.of(new HelenusPropertyNode(prop, parent)), metadata);

                        tupleMap.put(prop.getGetterMethod(), childDsl);

                    }
                }

            }
        }
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        String methodName = method.getName();

		if ("equals".equals(methodName) && method.getParameterCount() == 1) {
			Object otherObj = args[0];
			if (otherObj == null) {
				return false;
			}
			if (Proxy.isProxyClass(otherObj.getClass())) {
				return this == Proxy.getInvocationHandler(otherObj);
			}
			return false;
		}

		if (method.getParameterCount() != 0 || method.getReturnType() == void.class) {
			throw new HelenusException("invalid getter method " + method);
		}

		if ("hashCode".equals(methodName)) {
			return hashCode();
		}

		if ("toString".equals(methodName)) {
			return entity.toString();
		}

		if (DslExportable.GET_ENTITY_METHOD.equals(methodName)) {
			return entity;
		}

		if (DslExportable.GET_PARENT_METHOD.equals(methodName)) {
			return parent.get();
		}


		HelenusProperty prop = map.get(method);
		if (prop == null) {
		    prop = entity.getProperty(methodName);
        }

		if (prop != null) {

			AbstractDataType type = prop.getDataType();

			if (type instanceof UDTDataType) {

				Object childDsl = udtMap.get(method);

				if (childDsl != null) {
					return childDsl;
				}

			}

			if (type instanceof DTDataType) {
				DTDataType dataType = (DTDataType) type;
				DataType dt = dataType.getDataType();

				switch (dt.getName()) {

					case TUPLE :

						Object childDsl = tupleMap.get(method);

						if (childDsl != null) {
							return childDsl;
						}

						break;

					case SET :
						return new SetDsl(new HelenusPropertyNode(prop, parent));

					case LIST :
						return new ListDsl(new HelenusPropertyNode(prop, parent));

					case MAP :
						return new MapDsl(new HelenusPropertyNode(prop, parent));

					default :
						break;

				}

			}

			throw new DslPropertyException(new HelenusPropertyNode(prop, parent));
		}

		throw new HelenusException("invalid method call " + method);
	}

}