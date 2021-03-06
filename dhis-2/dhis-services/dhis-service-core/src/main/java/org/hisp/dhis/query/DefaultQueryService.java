package org.hisp.dhis.query;

/*
 * Copyright (c) 2004-2020, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;

import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.fieldfilter.Defaults;
import org.hisp.dhis.preheat.Preheat;
import org.hisp.dhis.query.planner.QueryPlan;
import org.hisp.dhis.query.planner.QueryPlanner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of QueryService which works with IdObjects.
 *
 * @author Morten Olav Hansen <mortenoh@gmail.com>
 */
@Slf4j
@Component( "org.hisp.dhis.query.QueryService" )
public class DefaultQueryService
    implements QueryService
{
    private final QueryParser queryParser;

    private final QueryPlanner queryPlanner;

    private final CriteriaQueryEngine<? extends IdentifiableObject> criteriaQueryEngine;

    private final InMemoryQueryEngine<? extends IdentifiableObject> inMemoryQueryEngine;

    @Autowired
    public DefaultQueryService( QueryParser queryParser, QueryPlanner queryPlanner,
        CriteriaQueryEngine<? extends IdentifiableObject> criteriaQueryEngine,
        InMemoryQueryEngine<? extends IdentifiableObject> inMemoryQueryEngine )
    {
        checkNotNull( queryParser );
        checkNotNull( queryPlanner );
        checkNotNull( criteriaQueryEngine );
        checkNotNull( inMemoryQueryEngine );

        this.queryParser = queryParser;
        this.queryPlanner = queryPlanner;
        this.criteriaQueryEngine = criteriaQueryEngine;
        this.inMemoryQueryEngine = inMemoryQueryEngine;
    }

    @Override
    public List<? extends IdentifiableObject> query( Query query )
    {
        return queryObjects( query );
    }

    @Override
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    public List<? extends IdentifiableObject> query( Query query, ResultTransformer transformer )
    {
        List<? extends IdentifiableObject> objects = queryObjects( query );

        if ( transformer != null )
        {
            return transformer.transform( objects );
        }

        return objects;
    }

    @Override
    public int count( Query query )
    {
        query.setFirstResult( 0 );
        query.setMaxResults( Integer.MAX_VALUE );

        return queryObjects( query ).size();
    }

    @Override
    public Query getQueryFromUrl( Class<?> klass, List<String> filters, List<Order> orders ) throws QueryParserException
    {
        return getQueryFromUrl( klass, filters, orders, Junction.Type.AND );
    }

    @Override
    public Query getQueryFromUrl( Class<?> klass, List<String> filters, List<Order> orders, Junction.Type rootJunction ) throws QueryParserException
    {
        Query query = queryParser.parse( klass, filters, rootJunction );
        query.addOrders( orders );

        return query;
    }

    //---------------------------------------------------------------------------------------------
    // Helper methods
    //---------------------------------------------------------------------------------------------

    private List<? extends IdentifiableObject> queryObjects( Query query )
    {
        List<? extends IdentifiableObject> objects = query.getObjects();

        if ( objects != null )
        {
            objects = inMemoryQueryEngine.query( query.setObjects( objects ) );
            clearDefaults( query.getSchema().getKlass(), objects, query.getDefaults() );

            return objects;
        }

        QueryPlan queryPlan = queryPlanner.planQuery( query );

        Query pQuery = queryPlan.getPersistedQuery();
        Query npQuery = queryPlan.getNonPersistedQuery();

        objects = criteriaQueryEngine.query( pQuery );

        if ( !npQuery.isEmpty() )
        {
            if ( log.isDebugEnabled() )
            {
                log.debug( "Doing in-memory for " + npQuery.getCriterions().size() + " criterions and "
                    + npQuery.getOrders().size() + " orders." );
            }

            npQuery.setObjects( objects );

            objects = inMemoryQueryEngine.query( npQuery );
        }

        clearDefaults( query.getSchema().getKlass(), objects, query.getDefaults() );

        return objects;
    }

    private void clearDefaults( Class<?> klass, List<? extends IdentifiableObject> objects, Defaults defaults )
    {
        if ( Defaults.INCLUDE == defaults || !Preheat.isDefaultClass( klass ) )
        {
            return;
        }

        objects.removeIf( object -> "default".equals( object.getName() ) );
    }
}
