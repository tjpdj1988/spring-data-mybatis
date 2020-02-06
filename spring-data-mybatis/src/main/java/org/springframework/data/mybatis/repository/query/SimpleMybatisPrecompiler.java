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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.persistence.EmbeddedId;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.SequenceGenerator;
import javax.persistence.SequenceGenerators;

import org.apache.ibatis.session.Configuration;

import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.data.mybatis.annotation.Condition;
import org.springframework.data.mybatis.annotation.Conditions;
import org.springframework.data.mybatis.annotation.Example;
import org.springframework.data.mybatis.mapping.MybatisMappingContext;
import org.springframework.data.mybatis.mapping.MybatisPersistentEntity;
import org.springframework.data.mybatis.mapping.MybatisPersistentProperty;
import org.springframework.data.mybatis.mapping.model.Column;
import org.springframework.data.mybatis.repository.MybatisExampleRepository;
import org.springframework.data.mybatis.repository.support.ResidentStatementName;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Simple mybatis precompiler.
 *
 * @author JARVIS SONG
 */
class SimpleMybatisPrecompiler extends AbstractMybatisPrecompiler {

	static final String DEFAULT_SEQUENCE_NAME = "seq_spring_data_mybatis";

	SimpleMybatisPrecompiler(MybatisMappingContext mappingContext, Configuration configuration,
			RepositoryInformation repositoryInformation) {
		super(mappingContext, configuration, repositoryInformation);
	}

	@Override
	protected String doPrecompile() {
		StringBuilder builder = new StringBuilder();
		builder.append(this.addResultMap());

		builder.append(this.addBaseColumnListSQL());

		builder.append(this.addInsertStatement(true));
		builder.append(this.addInsertStatement(false));
		builder.append(this.addUpdateStatement(true, true));
		builder.append(this.addUpdateStatement(true, false));
		builder.append(this.addUpdateStatement(false, true));
		builder.append(this.addUpdateStatement(false, false));
		builder.append(this.addDeleteByIdStatement());
		builder.append(this.addDeleteByIdsStatement());
		builder.append(this.addDeleteAllStatement());
		builder.append(this.addGetByIdStatement());
		builder.append(this.addFindStatement(true));
		builder.append(this.addFindStatement(false));
		builder.append(this.addCountAllStatement());
		builder.append(this.addCountStatement());

		if (MybatisExampleRepository.class.isAssignableFrom(this.repositoryInformation.getRepositoryInterface())) {
			if (this.persistentEntity.isAnnotationPresent(Example.class)) {

				try {
					ClassUtils.forName(this.persistentEntity.getType().getName() + "Example",
							this.persistentEntity.getType().getClassLoader());
				}
				catch (ClassNotFoundException ex) {
					throw new MappingException(
							"Are you forget to generate " + this.persistentEntity.getType().getName() + "Example ?");
				}

				builder.append(this.addExampleWhereClauseSQL());
				builder.append(this.addFindByExampleStatement());
			}
			else {
				throw new MappingException(String.format(
						"The %s extends MybatisExampleRepository, but could not find @Example on the entity: %s",
						this.repositoryInformation.getRepositoryInterface().getName(),
						this.persistentEntity.getType().getName()));
			}
		}
		return builder.toString();
	}

	private String addResultMap() {
		if (this.configuration.hasResultMap(this.namespace + '.' + ResidentStatementName.RESULT_MAP)) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		StringBuilder associationBuilder = new StringBuilder();
		this.persistentEntity.doWithProperties((PropertyHandler<MybatisPersistentProperty>) pp -> {
			if (pp.isAnnotationPresent(EmbeddedId.class) || pp.isEmbeddable()) {
				associationBuilder.append(String.format("<association property=\"%s\">", pp.getName()));
				MybatisPersistentEntity<?> embeddedEntity = this.mappingContext
						.getRequiredPersistentEntity(pp.getActualType());
				embeddedEntity.doWithProperties((PropertyHandler<MybatisPersistentProperty>) epp -> associationBuilder
						.append(this.resultMapSegment(epp.isIdProperty(), epp.getName(), epp.getColumn())));
				associationBuilder.append("</association>");
				return;
			}
			builder.append(this.resultMapSegment(pp.isIdProperty(), pp.getName(), pp.getColumn()));
		});

		builder.append(associationBuilder);
		return String.format("<resultMap id=\"%s\" type=\"%s\">%s</resultMap>", ResidentStatementName.RESULT_MAP,
				this.persistentEntity.getType().getName(), builder.toString());

	}

	private String addInsertStatement(boolean selective) {
		if (this.configuration.hasStatement(this.namespace + '.'
				+ (selective ? ResidentStatementName.INSERT_SELECTIVE : ResidentStatementName.INSERT))) {
			return "";
		}
		StringBuilder builder = new StringBuilder();

		MybatisPersistentProperty idProperty = this.persistentEntity.getIdProperty();
		String keyProperty = "";
		String keyColumn = "";
		boolean useGeneratedKeys = false;
		if (!this.persistentEntity.hasCompositeId() && null != idProperty) {
			keyProperty = idProperty.getName();
			keyColumn = idProperty.getColumn().getName().getText();
			if (idProperty.isAnnotationPresent(GeneratedValue.class)) {
				useGeneratedKeys = true;
				builder.append(this.buildKeyGenerator(idProperty));
			}
		}
		builder.append(selective ? this.buildInsertSelectiveSQL() : this.buildInsertSQL());
		return String.format(
				"<insert id=\"%s\" parameterType=\"%s\" keyProperty=\"%s\" keyColumn=\"%s\" useGeneratedKeys=\"%b\">%s</insert>",
				(selective ? ResidentStatementName.INSERT_SELECTIVE : ResidentStatementName.INSERT),
				this.persistentEntity.getType().getName(), keyProperty, keyColumn, useGeneratedKeys,
				builder.toString());

	}

	private String addUpdateStatement(boolean selective, boolean byId) {
		String statement = (selective
				? (byId ? ResidentStatementName.UPDATE_SELECTIVE_BY_ID : ResidentStatementName.UPDATE_SELECTIVE)
				: (byId ? ResidentStatementName.UPDATE_BY_ID : ResidentStatementName.UPDATE));
		if (this.configuration.hasStatement(this.namespace + '.' + statement)) {
			return "";
		}

		if (null == this.persistentEntity.getIdClass()) {
			return "";
		}

		String set = selective ? this.buildUpdateSetSelectiveSQL() : this.buildUpdateSetSQL();
		String where = byId ? this.buildByIdQueryCondition("", "__id")
				: this.buildByIdQueryCondition("__entity.", null);
		return String.format("<update id=\"%s\">update %s <set>%s</set> where %s</update>", statement,
				this.getTableName(), set, where);
	}

	private String buildUpdateSetSQL() {
		return this.mappingPropertyToColumn().entrySet().stream().filter(entry -> !entry.getValue().isPrimaryKey())
				.map(entry -> String.format("%s=%s", entry.getValue().getName().render(this.dialect),
						this.variableSegment("__entity." + entry.getKey(), entry.getValue())))
				.collect(Collectors.joining(","));
	}

	private String buildUpdateSetSelectiveSQL() {
		return this.mappingPropertyToColumn().entrySet().stream().filter(entry -> !entry.getValue().isPrimaryKey())
				.map(entry -> this.testNotNullSegment("__entity." + entry.getKey(),
						String.format("%s=%s", entry.getValue().getName().render(this.dialect),
								this.variableSegment("__entity." + entry.getKey(), entry.getValue()))))
				.collect(Collectors.joining(","));
	}

	private String buildInsertSQL() {
		Map<String, Column> propertyToColumn = this.mappingPropertyToColumn();
		String columns = propertyToColumn.keySet().stream()
				.map(k -> propertyToColumn.get(k).getName().render(this.dialect)).collect(Collectors.joining(","));
		String values = propertyToColumn.keySet().stream().map(k -> this.variableSegment(k, propertyToColumn.get(k)))
				.collect(Collectors.joining(","));
		String sql = String.format("insert into %s (%s) values (%s)", this.getTableName(), columns, values);
		return sql;
	}

	private String buildInsertSelectiveSQL() {
		Map<String, Column> propertyToColumn = this.mappingPropertyToColumn();
		String columns = propertyToColumn.keySet().stream()
				.map(k -> this.testNotNullSegment(k, propertyToColumn.get(k).getName().render(this.dialect) + ","))
				.collect(Collectors.joining());
		String values = propertyToColumn.keySet().stream()
				.map(k -> this.testNotNullSegment(k, this.variableSegment(k, propertyToColumn.get(k)) + ","))
				.collect(Collectors.joining());
		String sql = String.format("insert into %s " + //
				"<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">%s</trim> " + //
				"<trim prefix=\"values(\" suffix=\")\" suffixOverrides=\",\">%s</trim>", //
				this.getTableName(), columns, values);
		return sql;
	}

	private String buildKeyGenerator(MybatisPersistentProperty idProperty) {
		boolean executeBefore;
		String sql;
		GeneratedValue gv = idProperty.getRequiredAnnotation(GeneratedValue.class);
		if (gv.strategy() == GenerationType.IDENTITY || (gv.strategy() == GenerationType.AUTO
				&& "identity".equals(this.dialect.getNativeIdentifierGeneratorStrategy()))) {
			// identity
			sql = this.dialect.getIdentityColumnSupport().getIdentitySelectString(this.getTableName(),
					idProperty.getColumn().getName().getCanonicalName(),
					idProperty.getColumn().getJdbcType().TYPE_CODE);
			executeBefore = false;
		}
		else if (gv.strategy() == GenerationType.SEQUENCE || (gv.strategy() == GenerationType.AUTO
				&& "sequence".equals(this.dialect.getNativeIdentifierGeneratorStrategy()))) {
			String sequenceName = DEFAULT_SEQUENCE_NAME;
			if (StringUtils.hasText(gv.generator())) {
				// search sequence generator
				Map<String, String> sequenceGenerators = new HashMap<>();
				if (this.persistentEntity.isAnnotationPresent(SequenceGenerators.class)) {
					sequenceGenerators.putAll(
							Stream.of(this.persistentEntity.getRequiredAnnotation(SequenceGenerators.class).value())
									.filter(sg -> StringUtils.hasText(sg.sequenceName()))
									.collect(Collectors.toMap(sg -> sg.name(), sg -> sg.sequenceName())));
				}
				if (this.persistentEntity.isAnnotationPresent(SequenceGenerator.class)) {
					SequenceGenerator sg = this.persistentEntity.getRequiredAnnotation(SequenceGenerator.class);
					if (StringUtils.hasText(sg.sequenceName())) {
						sequenceGenerators.put(sg.name(), sg.sequenceName());
					}
				}
				if (idProperty.isAnnotationPresent(SequenceGenerators.class)) {
					sequenceGenerators
							.putAll(Stream.of(idProperty.getRequiredAnnotation(SequenceGenerators.class).value())
									.filter((sg) -> StringUtils.hasText(sg.sequenceName()))
									.collect(Collectors.toMap((sg) -> sg.name(), (sg) -> sg.sequenceName())));
				}
				if (idProperty.isAnnotationPresent(SequenceGenerator.class)) {
					SequenceGenerator sg = idProperty.getRequiredAnnotation(SequenceGenerator.class);
					if (StringUtils.hasText(sg.sequenceName())) {
						sequenceGenerators.put(sg.name(), sg.sequenceName());
					}
				}
				String sn = sequenceGenerators.get(gv.generator());
				if (StringUtils.hasText(sn)) {
					sequenceName = sn;
				}
			}
			sql = this.dialect.getSequenceNextValString(sequenceName);
			executeBefore = true;
		}
		else {
			throw new UnsupportedOperationException("unsupported generated value id strategy: " + gv.strategy());
		}
		return String.format(
				"<selectKey keyProperty=\"%s\" keyColumn=\"%s\" order=\"%s\" resultType=\"%s\">%s</selectKey>",
				idProperty.getName(), idProperty.getColumn().getName().getText(), executeBefore ? "BEFORE" : "AFTER",
				idProperty.getType().getName(), sql);
	}

	private String addDeleteByIdStatement() {
		if (this.configuration.hasStatement(this.namespace + '.' + ResidentStatementName.DELETE_BY_ID, false)) {
			return "";
		}
		if (null == this.persistentEntity.getIdClass()) {
			return "";
		}

		return String.format("<delete id=\"%s\" parameterType=\"%s\">delete from %s where %s</delete>",
				ResidentStatementName.DELETE_BY_ID, this.persistentEntity.getIdClass().getName(), this.getTableName(),
				this.buildByIdQueryCondition());
	}

	private String addDeleteByIdsStatement() {
		if (this.configuration.hasStatement(this.namespace + '.' + ResidentStatementName.DELETE_BY_IDS, false)) {
			return "";
		}
		return String.format("<delete id=\"%s\">delete from %s where %s</delete>", ResidentStatementName.DELETE_BY_IDS,
				this.getTableName(), this.buildByIdsQueryCondition());
	}

	private String addDeleteAllStatement() {
		if (this.configuration.hasStatement(this.namespace + '.' + ResidentStatementName.DELETE_ALL, false)) {
			return "";
		}
		return String.format("<delete id=\"%s\">delete from %s</delete>", ResidentStatementName.DELETE_ALL,
				this.getTableName());
	}

	private String addGetByIdStatement() {
		if (this.configuration.hasStatement(this.namespace + '.' + ResidentStatementName.GET_BY_ID, false)) {
			return "";
		}
		if (null == this.persistentEntity.getIdClass()) {
			return "";
		}
		String sql = String.format("select * from %s where %s", this.getTableName(), this.buildByIdQueryCondition());
		return String.format("<select id=\"%s\" parameterType=\"%s\" resultMap=\"%s\">%s</select>",
				ResidentStatementName.GET_BY_ID, this.persistentEntity.getIdClass().getName(),
				ResidentStatementName.RESULT_MAP, sql);
	}

	private String buildByIdQueryCondition() {

		MybatisPersistentProperty idProperty = this.persistentEntity.getRequiredIdProperty();
		if (idProperty.isAnnotationPresent(EmbeddedId.class)) {
			MybatisPersistentEntity<?> embeddedEntity = this.mappingContext
					.getRequiredPersistentEntity(idProperty.getActualType());
			return this
					.findProperties(embeddedEntity).stream().map(epp -> String.format("%s = %s.%s",
							epp.getColumn().getName().render(this.dialect), idProperty.getName(), epp.getName()))
					.collect(Collectors.joining(" and "));
		}

		return this
				.findProperties().stream().filter(PersistentProperty::isIdProperty).map(p -> String.format("%s = %s",
						p.getColumn().getName().render(this.dialect), this.variableSegment(p.getName(), p.getColumn())))
				.collect(Collectors.joining(" and "));
	}

	private String buildByIdQueryCondition(String propertyPrefix, String idPropertyName) {
		MybatisPersistentProperty idProperty = this.persistentEntity.getRequiredIdProperty();
		final String prefix = (null != propertyPrefix) ? propertyPrefix : "";
		final String name = StringUtils.isEmpty(idPropertyName) ? idProperty.getName() : idPropertyName;

		if (idProperty.isAnnotationPresent(EmbeddedId.class)) {
			MybatisPersistentEntity<?> embeddedEntity = this.mappingContext
					.getRequiredPersistentEntity(idProperty.getActualType());
			return this
					.findProperties(embeddedEntity).stream().map(epp -> String.format("%s = %s.%s",
							epp.getColumn().getName().render(this.dialect), prefix + name, epp.getName()))
					.collect(Collectors.joining(" and "));
		}

		return this.findProperties().stream().filter(PersistentProperty::isIdProperty).map(p -> String.format("%s = %s",
				p.getColumn().getName().render(this.dialect), this.variableSegment(prefix + name, p.getColumn())))
				.collect(Collectors.joining(" and "));
	}

	private String addFindStatement(boolean pageable) {
		if (this.configuration.hasStatement(
				this.namespace + '.' + ((pageable) ? ResidentStatementName.FIND_BY_PAGER : ResidentStatementName.FIND),
				false)) {
			return "";
		}
		String conditions = String.format("<where>%s%s</where> %s", this.buildByIdsQueryCondition(),
				this.buildByConditionQueryCondition(), this.buildStandardOrderBySegment());
		String sql = String.format("select * from %s %s", this.getTableName(), conditions);
		return String.format("<select id=\"%s\" resultMap=\"%s\">%s</select>",
				pageable ? ResidentStatementName.FIND_BY_PAGER : ResidentStatementName.FIND,
				ResidentStatementName.RESULT_MAP,
				pageable ? this.dialect.getLimitHandler().processSql(sql, null) : sql);
	}

	private String buildByConditionQueryCondition() {

		this.findProperties().stream().map(pp -> {
			Set<Condition> set = new HashSet<>();
			Conditions conditions = pp.findAnnotation(Conditions.class);
			if (null != conditions && conditions.value().length > 0) {
				set.addAll(Arrays.asList(conditions.value()));
			}
			Condition condition = pp.findAnnotation(Condition.class);
			if (null != condition) {
				set.add(condition);
			}
			if (set.isEmpty()) {
				return "";
			}

			return set.stream().map(c -> {
				String[] properties = c.properties();
				if (null == properties || properties.length == 0) {
					properties = new String[] { pp.getName() };
				}

				Part.Type type = Part.Type.valueOf(c.type().name());
				if (type.getNumberOfArguments() > 0 && type.getNumberOfArguments() != properties.length) {
					throw new MappingException("@Condition with type " + type + " needs " + type.getNumberOfArguments()
							+ " arguments, but only find " + properties.length + " properties in this @Condition.");
				}
				String cond = Stream.of(properties).map(property -> String.format("__condition.%s != null", property))
						.collect(Collectors.joining(" and "));
				Part.IgnoreCaseType ignoreCaseType = Part.IgnoreCaseType.valueOf(c.ignoreCaseType().name());
				String columnName = StringUtils.hasText(c.column()) ? c.column()
						: pp.getColumn().getName().render(this.dialect);
				String left = (ignoreCaseType == Part.IgnoreCaseType.ALWAYS
						|| ignoreCaseType == Part.IgnoreCaseType.WHEN_POSSIBLE)
								? (this.dialect.getLowercaseFunction() + '(' + columnName + ')') : columnName;
				String operator = this.buildQueryByConditionOperator(type);
				String right = this.buildQueryByConditionRightSegment(type, ignoreCaseType, properties);
				return String.format("<if test=\"%s\"> and %s %s %s</if>", cond, left, operator, right);
			}).collect(Collectors.joining());
		});
		String sql = "";
		return String.format("<if test=\"__condition != null\"><trim prefixOverrides=\"and |or \">%s</trim></if>", sql);
	}

	private String buildStandardOrderBySegment() {
		String mapping = this.mappingPropertyToColumn().entrySet().stream()
				.map(entry -> String.format("&apos;%s&apos;:&apos;%s&apos;", entry.getKey(),
						entry.getValue().getName().render(this.dialect)))
				.collect(Collectors.joining(","));
		String bind = String.format("<bind name=\"__columnsMap\" value='#{%s}'/>", mapping);
		String sql = String.format("order by "
				+ " <foreach collection=\"__sort\" item=\"item\" index=\"idx\" open=\"\" close=\"\" separator=\",\">"
				+ "<if test=\"item.ignoreCase\">%s(</if>" + "${__columnsMap[item.property]}"
				+ "<if test=\"item.ignoreCase\">)</if> " + "${item.direction.name().toLowerCase()}" + "</foreach>",
				this.dialect.getLowercaseFunction());
		return String.format("<if test=\"__sort != null\">%s %s</if>", bind, sql);
	}

	private String buildByIdsQueryCondition() {
		if (null == this.persistentEntity.getIdClass()) {
			return "";
		}

		String sql;
		MybatisPersistentProperty idProperty = this.persistentEntity.getRequiredIdProperty();
		if (idProperty.isAnnotationPresent(EmbeddedId.class)) {
			String conditions = this
					.findProperties(this.mappingContext.getRequiredPersistentEntity(idProperty.getActualType()))
					.stream().map(epp -> String.format("%s = #{item.%s}",
							epp.getColumn().getName().render(this.dialect), epp.getName()))
					.collect(Collectors.joining(" and "));
			sql = String.format(
					"<foreach collection=\"__ids\" item=\"item\" index=\"index\" open=\"(\" separator=\") or (\" close=\")\">%s</foreach>",
					conditions);
		}
		else {
			sql = String.format(
					"%s in <foreach collection=\"__ids\" item=\"item\" index=\"index\" open=\"(\" separator=\",\" close=\")\">#{item}</foreach>",
					idProperty.getColumn().getName().render(this.dialect));
		}
		return String.format("<if test=\"__ids != null\"><trim prefixOverrides=\"and |or \"> and %s</trim></if>", sql);
	}

	private String buildQueryByConditionOperator(Part.Type type) {
		switch (type) {
		case BETWEEN:
			return " between ";
		case SIMPLE_PROPERTY:
			return "=";
		case NEGATING_SIMPLE_PROPERTY:
			return "<![CDATA[<>]]>";
		case LESS_THAN:
		case BEFORE:
			return "<![CDATA[<]]>";
		case LESS_THAN_EQUAL:
			return "<![CDATA[<=]]>";
		case GREATER_THAN:
		case AFTER:
			return "<![CDATA[>]]>";
		case GREATER_THAN_EQUAL:
			return ">=";
		case NOT_LIKE:
		case NOT_CONTAINING:
			return " not like ";
		case LIKE:
		case STARTING_WITH:
		case ENDING_WITH:
		case CONTAINING:
			return " like ";
		case IN:
			return " in ";
		case NOT_IN:
			return " not in ";
		}

		return "";
	}

	private String buildQueryByConditionRightSegment(Part.Type type, Part.IgnoreCaseType ignoreCaseType,
			String[] properties) {
		switch (type) {
		case BETWEEN:
			return String.format("#{%s} and #{%s}", properties[0], properties[1]);
		case IS_NOT_NULL:
			return " is not null";
		case IS_NULL:
			return " is null";
		case STARTING_WITH:
			return this.buildLikeRightSegment(properties[0], false, true, ignoreCaseType);
		case ENDING_WITH:
			return this.buildLikeRightSegment(properties[0], true, false, ignoreCaseType);
		case NOT_CONTAINING:
		case CONTAINING:
			return this.buildLikeRightSegment(properties[0], true, true, ignoreCaseType);
		case NOT_IN:
		case IN:
			return String.format(
					"<foreach item=\"__item\" index=\"__index\" collection=\"%s\" open=\"(\" separator=\",\" close=\")\">#{__item}</foreach>",
					properties[0]);
		case TRUE:
			return " = true";
		case FALSE:
			return " = false";
		default:
			if (ignoreCaseType == Part.IgnoreCaseType.ALWAYS || ignoreCaseType == Part.IgnoreCaseType.WHEN_POSSIBLE) {
				return String.format("%s(#{%s})", this.dialect.getLowercaseFunction(), properties[0]);
			}
			return String.format("#{%s}", properties[0]);

		}
	}

	private String buildLikeRightSegment(String property, boolean left, boolean right,
			Part.IgnoreCaseType ignoreCaseType) {
		return String.format("<bind name=\"__bind_%s\" value=\"%s%s%s\" />%s", property, (left ? "'%' + " : ""),
				property, (right ? " + '%'" : ""),
				(((ignoreCaseType == Part.IgnoreCaseType.ALWAYS)
						|| (ignoreCaseType == Part.IgnoreCaseType.WHEN_POSSIBLE))
								? String.format("%s(#{__bind_%s})", this.dialect.getLowercaseFunction(), property)
								: String.format("#{__bind_%s}", property)));
	}

	private String addCountAllStatement() {
		if (this.configuration.hasStatement(this.namespace + '.' + ResidentStatementName.COUNT_ALL, false)) {
			return "";
		}
		return String.format("<select id=\"%s\" resultType=\"long\">select count(*) from %s</select>",
				ResidentStatementName.COUNT_ALL, this.getTableName());
	}

	private String addCountStatement() {
		if (this.configuration.hasStatement(this.namespace + '.' + ResidentStatementName.COUNT, false)) {
			return "";
		}

		String where = this.buildByConditionQueryCondition();
		return String.format(
				"<select id=\"%s\" resultType=\"long\">select count(*) from %s <where><if test=\"__condition != null\"><trim prefixOverrides=\"and |or \">%s</trim></if></where></select>",
				ResidentStatementName.COUNT, this.getTableName(), where);
	}

	private String addFindByExampleStatement() {
		if (this.configuration.hasStatement(this.namespace + '.' + ResidentStatementName.FIND_BY_EXAMPLE, false)) {
			return "";
		}

		return String.format("<select id=\"%s\" parameterType=\"%sExample\" resultMap=\"%s\">" + "select "
				+ "<if test=\"distinct\">distinct</if>" + "<include refid=\"__base_column_list\"/> " + "from %s "
				+ "<if test=\"_parameter != null\">" + "<include refid=\"__example_where_clause\"/>" + "</if>"
				+ "<if test=\"orderByClause != null\"> " + "order by ${orderByClause} " + "</if>" + "</select>",
				ResidentStatementName.FIND_BY_EXAMPLE, this.persistentEntity.getType().getName(),
				ResidentStatementName.RESULT_MAP, this.getTableName());
	}

	private String addBaseColumnListSQL() {
		String sql = this.mappingPropertyToColumn().values().stream().map(c -> c.getName().render(this.dialect))
				.collect(Collectors.joining(","));
		return String.format("<sql id=\"__base_column_list\">%s</sql>", sql);
	}

	private String addExampleWhereClauseSQL() {
		return "<sql id=\"__example_where_clause\">" + "<where>"
				+ "<foreach collection=\"oredCriteria\" item=\"criteria\" separator=\"or\">"
				+ "<if test=\"criteria.valid\">" + "<trim prefix=\"(\" prefixOverrides=\"and\" suffix=\")\">"
				+ "<foreach collection=\"criteria.criteria\" item=\"criterion\">" + "<choose>"
				+ "<when test=\"criterion.noValue\"> " + "and ${criterion.condition}" + "</when>"
				+ "<when test=\"criterion.singleValue\"> " + "and ${criterion.condition} #{criterion.value}" + "</when>"
				+ "<when test=\"criterion.betweenValue\"> "
				+ "and ${criterion.condition} #{criterion.value} and #{criterion.secondValue}" + "</when>"
				+ "<when test=\"criterion.listValue\"> " + "and ${criterion.condition}"
				+ "<foreach close=\")\" collection=\"criterion.value\" item=\"listItem\" open=\"(\" separator=\",\">"
				+ "#{listItem}" + "</foreach>" + "</when>" + "</choose>" + "</foreach>" + "</trim>" + "</if>"
				+ "</foreach>" + "</where>" + "</sql>";
	}

}
