/*
 *      Copyright (C) 2015 Noorq, Inc.
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
package casser.mapping;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import casser.support.CasserException;
import casser.support.CasserMappingException;

import com.datastax.driver.core.UserType;
import com.datastax.driver.core.schemabuilder.UDTType;

public class CasserMappingRepository {

	private static final Optional<CasserEntityType> OPTIONAL_UDT = Optional.of(CasserEntityType.USER_DEFINED_TYPE);
	
	private final Map<Class<?>, CasserMappingEntity> entityMap = new HashMap<Class<?>, CasserMappingEntity>();

	private final Map<String, UserType> userTypeMap = new HashMap<String, UserType>();
	
	private boolean readOnly = false;
	
	public CasserMappingRepository setReadOnly() {
		this.readOnly = true;
		return this;
	}
	
	public void addUserType(String name, UserType userType) {
		
		if (readOnly) {
			throw new CasserException("read-only mode");
		}
		
		userTypeMap.putIfAbsent(name.toLowerCase(), userType);
		
	}
	
	public UserType findUserType(String name) {
		return userTypeMap.get(name.toLowerCase());
	}

	public void add(Object dsl) {
		add(dsl, Optional.empty());
	}
	
	public void add(Object dsl, Optional<CasserEntityType> type) {

		if (readOnly) {
			throw new CasserException("read-only mode");
		}

		Class<?> iface = MappingUtil.getMappingInterface(dsl);
		
		if (!entityMap.containsKey(iface)) {

			CasserMappingEntity entity = type.isPresent() ? 
					new CasserMappingEntity(iface, type.get()) :
						new CasserMappingEntity(iface);

			if (null == entityMap.putIfAbsent(iface, entity)) {
				
				addUserDefinedTypes(entity.getMappingProperties());
				
			}

		}
		
	}
	
	private void addUserDefinedTypes(Collection<CasserMappingProperty> props) {
		
		for (CasserMappingProperty prop : props) {
			
			UDTType type = prop.getUDTType();
			
			if (type != null) {
				
				add(prop.getJavaType(), OPTIONAL_UDT);
				
			}
			
		}
		
	}

	public Collection<CasserMappingEntity> entities() {
		return Collections.unmodifiableCollection(entityMap.values());
	}
	
	public CasserMappingEntity getEntity(Class<?> iface) {
		
		CasserMappingEntity entity = entityMap.get(iface);
		
		if (entity == null) {
			throw new CasserMappingException("please add all entities in SessionInitializer, unknown entity " + iface);
		}
		
		return entity;
	}
	
}
