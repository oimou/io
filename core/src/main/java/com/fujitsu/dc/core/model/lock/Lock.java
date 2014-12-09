/**
 * personium.io
 * Copyright 2014 FUJITSU LIMITED
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
package com.fujitsu.dc.core.model.lock;

import java.io.Serializable;

/**
 * Lockオブジェクト.
 */
public class Lock implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * ODataを扱うときに使うLockカテゴリ.
     */
    public static final String CATEGORY_ODATA = "odata";

    /**
     * Davを扱うときに使うLockカテゴリ.
     */
    public static final String CATEGORY_DAV = "dav";

    /**
     * Cellを扱うときに使うLockカテゴリ.
     */
    public static final String CATEGORY_CELL = "Cell";

    /**
     * UnitUserごとのデータアクセスを一時的に参照モードにするLockカテゴリ.
     */
    public static final String CATEGORY_REFERENCE_ONLY = "referenceOnly";

    String fullKey;
    Long createdAt;

    /**
     * コンストラクタ(非公開).
     * @param key ロックのキー
     */
    Lock(String fullKey, Long createdAt) {
        this.fullKey = fullKey;
        this.createdAt = createdAt;
    }

    /**
     * ロックをリリースします.
     */
    public void release() {
        LockManager.releaseLock(this.fullKey);
    }
}