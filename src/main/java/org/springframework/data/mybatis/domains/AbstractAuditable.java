/*
 *
 *   Copyright 2016 the original author or authors.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package org.springframework.data.mybatis.domains;

import org.springframework.data.mybatis.annotations.Column;
import org.springframework.data.mybatis.annotations.MappedSuperclass;

import java.io.Serializable;

/**
 * Auditable Basic Entity.
 *
 * @param <PK> Primary key's type.
 * @author Jarvis Song
 */
@MappedSuperclass
public abstract class AbstractAuditable<PK extends Serializable> extends AbstractAuditableDateTime<PK> implements Auditable<PK> {

    private static final long serialVersionUID = 3732216348067110394L;

    @Column(name = "CREATOR")
    protected Long createdBy;

    @Column(name = "MODIFIER")
    protected Long lastModifiedBy;

    @Override
    public Long getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public Long getLastModifiedBy() {
        return lastModifiedBy;
    }

    @Override
    public void setLastModifiedBy(Long lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

}
