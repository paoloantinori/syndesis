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
package io.syndesis.server.logging.jsondb.service;

import io.syndesis.server.endpoint.v1.handler.activity.Activity;
import io.syndesis.server.jsondb.impl.SqlJsonDB;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.skife.jdbi.v2.DBI;

import java.io.IOException;
import java.util.List;

/**
 * Used to unit test the LogsController implementation.
 */
public class DBActivityTrackingServiceTest {

    private SqlJsonDB jsondb;
    private DBI dbi;
    private DBActivityTrackingService dbats;

    @Before
    public void before() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:u;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        this.dbi = new DBI(ds);
        this.jsondb = new SqlJsonDB(dbi, null);
        this.jsondb.createTables();
        this.jsondb.set( "/activity/exchanges/my-integration1/i-L38cZ51d1L876xV4vEFz/", "\"{\\\"id\\\":\\\"i-L38cZ51d1L876xV4vEFz\\\",\\\"logts\\\":\\\"2018-01-12T21:22:02.068338027Z\\\",\\\"at\\\":1516285084034,\\\"pod\\\":\\\"test-pod-x23x\\\",\\\"ver\\\":\\\"3\\\",\\\"status\\\":\\\"done\\\",\\\"failed\\\":false,\\\"steps\\\":[{\\\"id\\\":\\\"s2\\\",\\\"at\\\":1516285084052,\\\"duration\\\":582977,\\\"messages\\\":[\\\"Hello World\\\"]},{\\\"id\\\":\\\"s4\\\",\\\"at\\\":1516285084057,\\\"duration\\\":494949}]}\"");
        this.jsondb.set( "/activity/exchanges/my-integration2/i-L39cZ5Pd1L876xV4vELz/", "\"{\\\"id\\\":\\\"i-L39cZ5Pd1L876xV4vELz\\\",\\\"logts\\\":\\\"2018-01-12T21:22:02.068338027Z\\\",\\\"at\\\":1516285084058,\\\"pod\\\":\\\"test-pod-x23x\\\",\\\"ver\\\":\\\"3\\\",\\\"status\\\":\\\"done\\\",\\\"failed\\\":true}\"" );

        dbats = new DBActivityTrackingService( jsondb );
    }

    @Test
    public void testNullSteps() throws IOException {
        List<Activity> activities = dbats.getActivities( "my-integration1", "my-integration1", 10);
        Assert.assertEquals( 2, activities.get(0).getSteps().size());
        activities = dbats.getActivities( "my-integration2", "my-integration2", 10);
        Assert.assertTrue( activities.get(0).getSteps().isEmpty());
    }

}
