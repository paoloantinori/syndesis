/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.jsondb;

import io.syndesis.model.ToJson;

/**
 * Options that can be configured on the {@link JsonDB#getAsString(String, GetOptions)}.
 */
public class GetOptions implements ToJson, Cloneable {

    private boolean prettyPrint;
    private Integer depth;
    private String callback;
    private String startingAt;
    private Integer limit;

    public boolean prettyPrint() {
        return prettyPrint;
    }

    public Integer depth() {
        return depth;
    }

    public String callback() {
        return callback;
    }

    public GetOptions prettyPrint(final boolean prettyPrint) {
        this.prettyPrint = prettyPrint;
        return this;
    }

    public GetOptions depth(final Integer depth) {
        this.depth = depth;
        return this;
    }

    public GetOptions callback(final String callback) {
        this.callback = callback;
        return this;
    }

    @Override
    public GetOptions clone() throws CloneNotSupportedException{
        return (GetOptions) super.clone();
    }

    public String after() {
        return startingAt;
    }

    public GetOptions after(String startingAt) {
        this.startingAt = startingAt;
        return this;
    }

    public Integer limit() {
        return limit;
    }

    public GetOptions limit(Integer limit) {
        this.limit = limit;
        return this;
    }
}
