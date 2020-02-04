/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mybatis.repository.query;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.Tuple;
import javax.persistence.TupleElement;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.ResultProcessor;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.data.util.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Abstract base class to implement {@link RepositoryQuery}s.
 *
 * @author JARVIS SONG
 * @since 1.0.0
 */
public abstract class AbstractMybatisQuery implements RepositoryQuery {

	private final MybatisQueryMethod method;

	private final Lazy<MybatisQueryExecution> execution;

	public AbstractMybatisQuery(MybatisQueryMethod method) {
		Assert.notNull(method, "MybatisQueryMethod must not be null!");

		this.method = method;

		this.execution = Lazy.of(() -> {
			return new MybatisQueryExecution.SingleEntityExecution();
		});
	}

	@Override
	public MybatisQueryMethod getQueryMethod() {
		return this.method;
	}

	@Override
	public Object execute(Object[] parameters) {
		return this.doExecute(this.getExecution(), parameters);
	}

	@Nullable
	private Object doExecute(MybatisQueryExecution execution, Object[] values) {

		MybatisParametersParameterAccessor accessor = new MybatisParametersParameterAccessor(
				this.method.getParameters(), values);
		Object result = execution.execute(this, accessor);

		ResultProcessor withDynamicProjection = this.method.getResultProcessor().withDynamicProjection(accessor);
		return withDynamicProjection.processResult(result, new TupleConverter(withDynamicProjection.getReturnedType()));
	}

	protected MybatisQueryExecution getExecution() {

		MybatisQueryExecution execution = this.execution.getNullable();

		if (null != execution) {
			return execution;
		}

		if (this.method.isModifyingQuery()) {
			return new MybatisQueryExecution.ModifyingExecution(this.method);
		}
		else {
			return new MybatisQueryExecution.SingleEntityExecution();
		}
	}

	static class TupleConverter implements Converter<Object, Object> {

		private final ReturnedType type;

		TupleConverter(ReturnedType type) {

			Assert.notNull(type, "Returned type must not be null!");

			this.type = type;
		}

		@Override
		public Object convert(Object source) {

			if (!(source instanceof Tuple)) {
				return source;
			}

			Tuple tuple = (Tuple) source;
			List<TupleElement<?>> elements = tuple.getElements();

			if (elements.size() == 1) {

				Object value = tuple.get(elements.get(0));

				if (this.type.isInstance(value) || value == null) {
					return value;
				}
			}

			return new TupleBackedMap(tuple);
		}

		private static class TupleBackedMap implements Map<String, Object> {

			private static final String UNMODIFIABLE_MESSAGE = "A TupleBackedMap cannot be modified.";

			private final Tuple tuple;

			TupleBackedMap(Tuple tuple) {
				this.tuple = tuple;
			}

			@Override
			public int size() {
				return this.tuple.getElements().size();
			}

			@Override
			public boolean isEmpty() {
				return this.tuple.getElements().isEmpty();
			}

			@Override
			public boolean containsKey(Object key) {

				try {
					this.tuple.get((String) key);
					return true;
				}
				catch (IllegalArgumentException ex) {
					return false;
				}
			}

			@Override
			public boolean containsValue(Object value) {
				return Arrays.asList(this.tuple.toArray()).contains(value);
			}

			@Override
			@Nullable
			public Object get(Object key) {

				if (!(key instanceof String)) {
					return null;
				}

				try {
					return this.tuple.get((String) key);
				}
				catch (IllegalArgumentException ex) {
					return null;
				}
			}

			@Override
			public Object put(String key, Object value) {
				throw new UnsupportedOperationException(UNMODIFIABLE_MESSAGE);
			}

			@Override
			public Object remove(Object key) {
				throw new UnsupportedOperationException(UNMODIFIABLE_MESSAGE);
			}

			@Override
			public void putAll(Map<? extends String, ?> m) {
				throw new UnsupportedOperationException(UNMODIFIABLE_MESSAGE);
			}

			@Override
			public void clear() {
				throw new UnsupportedOperationException(UNMODIFIABLE_MESSAGE);
			}

			@Override
			public Set<String> keySet() {

				return this.tuple.getElements().stream() //
						.map(TupleElement::getAlias) //
						.collect(Collectors.toSet());
			}

			@Override
			public Collection<Object> values() {
				return Arrays.asList(this.tuple.toArray());
			}

			@Override
			public Set<Entry<String, Object>> entrySet() {

				return this.tuple.getElements().stream() //
						.map(e -> new HashMap.SimpleEntry<String, Object>(e.getAlias(), this.tuple.get(e))) //
						.collect(Collectors.toSet());
			}

		}

	}

}