package eu.openminted.content.openaire;

import eu.openminted.content.connector.Query;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.client.solrj.impl.BinaryRequestWriter;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CursorMarkParams;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

@SuppressWarnings("WeakerAccess")
public class OpenAireSolrClient {
    private static Logger log = Logger.getLogger(OpenAireContentConnector.class.getName());

    private int rows = 10;
    private int start = 0;

    private String defaultCollection;
    private String hosts;
    private int queryLimit = 0;
    private String type;

    private OpenAireSolrClient() {
    }

    public OpenAireSolrClient(String type, String hosts, String defaultCollection, int queryLimit) {
        this.type = type;
        this.hosts = hosts;
        this.defaultCollection = defaultCollection;
        this.queryLimit = queryLimit;
    }

    /***
     * Search method for browsing metadata
     * @param query the query as inserted in Content-OpenAireContentConnector-Service
     * @return QueryResponse with metadata and facets
     */
    public QueryResponse query(Query query) {
        QueryResponse queryResponse = null;
        SolrQuery solrQuery = queryBuilder(query);
        SolrClient solrClient = getSolrClient();

        assert (solrClient != null);

        try {
            if (defaultCollection != null && !defaultCollection.isEmpty()) {
                queryResponse = solrClient.query(defaultCollection, solrQuery);
            }
        } catch (SolrServerException | IOException e) {
            log.error(e);
        } finally {
            try {
                solrClient.close();
            } catch (IOException e) {
                log.error("Inner message", e);
            }
        }
        return queryResponse;
    }

    /***
     * Method for downloading metadata where the query's criteria are applicable
     * @param query the query as inserted in Content-OpenAireContentConnector-Service
     */
    public void fetchMetadata(Query query, StreamingResponseCallback streamingResponseCallback) throws IOException {
        if (streamingResponseCallback == null) return;

        SolrQuery solrQuery = queryBuilder(query);
        String cursorMark = CursorMarkParams.CURSOR_MARK_START;
        boolean done = false;

        try (SolrClient solrClient = getSolrClient()) {

            assert (solrClient != null);

            int count = 0;
            while (!done && !(queryLimit > 0 && count >= queryLimit)) {
                solrQuery.set(CursorMarkParams.CURSOR_MARK_PARAM, cursorMark);
                QueryResponse rsp = solrClient.queryAndStreamResponse(defaultCollection,
                        solrQuery,
                        streamingResponseCallback);

                count += this.rows;

                String nextCursorMark = rsp.getNextCursorMark();
                if (cursorMark.equals(nextCursorMark)) {
                    done = true;
                }
                cursorMark = nextCursorMark;
            }
        } catch (SolrServerException e) {
            log.info("Fetching metadata has been interrupted. See Debug for more information!");
            log.debug("OpenAireSolrClient.fetchMetadata", e);
        }
    }

    /***
     * Method to index a SolrInputDocument
     * @param solrInputDocument the document that is going to be indexed
     */
    public void commit(SolrInputDocument solrInputDocument) {
        try {
            String localHost = "http://adonis.athenarc.gr:8983/solr/adonis-index-openaire/";

            HttpSolrClient solrClient = new HttpSolrClient.Builder(localHost).build();
            solrClient.setRequestWriter(new BinaryRequestWriter());

            solrClient.add(solrInputDocument);
            solrClient.commit();
        } catch (IOException | SolrServerException e) {
            e.printStackTrace();
        }
    }

    /***
     * Converts the query to the equivalent SolrQuery
     * @param query the query as inserted in Content-OpenAireContentConnector-Service
     * @return the SolrQuery that corresponds to input query.
     */
    public SolrQuery queryBuilder(Query query) {
        String FILTER_QUERY_RESULT_TYPE_NAME = "resulttypename:publication";
        String FILTER_QUERY_DELETED_BY_INFERENCE = "deletedbyinference:false";

        if (query.getFrom() > 0) {
            this.start = query.getFrom();
        }

        if (query.getTo() > 0) {
            this.rows = query.getTo() - this.start;
        }

        SolrQuery solrQuery = (new SolrQuery()).setStart(this.start).setRows(this.rows);

        if (query.getFacets() != null) {
            solrQuery.setFacet(true);

            if (query.getFacets().size() > 0) {
                solrQuery.addFacetField(query.getFacets().toArray(new String[query.getFacets().size()]));
            }
        }

        if (query.getParams() != null) {

            for (String key : query.getParams().keySet()) {
                if (key.equalsIgnoreCase("sort")) {
                    for (String sortField : query.getParams().get("sort")) {
                        String[] sortingParameter = sortField.split(" ");
                        if (sortingParameter.length == 2) {
                            SolrQuery.ORDER order = SolrQuery.ORDER.valueOf(sortingParameter[1]);
                            solrQuery.setSort(sortingParameter[0], order);
                        } else if (sortingParameter.length == 1) {
                            solrQuery.setSort(sortingParameter[0], SolrQuery.ORDER.desc);
                        }
                    }
                } else if (key.equalsIgnoreCase("fl")) {
                    for (String field : query.getParams().get("fl")) {
                        solrQuery.addField(field);
                    }
                } else {
                    List<String> vals = query.getParams().get(key);

                    if (key.toLowerCase().contains("year") || key.toLowerCase().contains("date")) {
                        SimpleDateFormat yearFormat = new SimpleDateFormat("YYYY");
                        SimpleDateFormat queryDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
                        TimeZone UTC = TimeZone.getTimeZone("UTC");
                        queryDateFormat.setTimeZone(UTC);
                        StringBuilder datetimeFieldQuery = new StringBuilder();
                        for (String val : vals) {
                            Date date;
                            String queryDate;
                            try {

                                // try parse year with yearFormat "YYYY".
                                // If it is successful add to the year the
                                // rest datetime that is necessary for solr
                                // to parse and parse it with the proper
                                // queryDateFormat

                                yearFormat.parse(val);
                                val = val + "-01-01T00:00:00.000Z";

                                date = queryDateFormat.parse(val);
                                queryDate = queryDateFormat.format(date);
                                datetimeFieldQuery.append(key).append(":[").append(queryDate).append(" TO ").append(queryDate).append("+1YEAR] OR ");
                            } catch (ParseException e) {
                                try {
                                    date = queryDateFormat.parse(val);
                                    queryDate = queryDateFormat.format(date);
                                    datetimeFieldQuery.append(key).append(":[").append(queryDate).append(" TO ").append(queryDate).append("+1YEAR] OR ");
                                } catch (ParseException e1) {
                                    e1.printStackTrace();
                                }
                            }
                        }
                        datetimeFieldQuery = new StringBuilder(datetimeFieldQuery.toString().replaceAll(" OR $", ""));
                        solrQuery.addFilterQuery(datetimeFieldQuery.toString());
                    } else {
                        StringBuilder fieldQuery = new StringBuilder();
                        for (String val : vals) {
                            fieldQuery.append(key).append(":").append("\"" + val + "\"").append(" OR ");
                        }
                        fieldQuery = new StringBuilder(fieldQuery.toString().replaceAll(" OR $", ""));
                        solrQuery.addFilterQuery(fieldQuery.toString());
                    }
                }
            }
        }

        solrQuery.addFilterQuery(FILTER_QUERY_RESULT_TYPE_NAME);
        solrQuery.addFilterQuery(FILTER_QUERY_DELETED_BY_INFERENCE);

        solrQuery.setQuery(query.getKeyword());

        log.info(solrQuery.toString());

        return solrQuery;
    }

    private SolrClient getSolrClient() {
        if (type.equalsIgnoreCase(CloudSolrClient.class.getName())) {
            return new CloudSolrClient.Builder().withZkHost(hosts).build();
        } else if (type.equalsIgnoreCase(HttpSolrClient.class.getName())) {
            return new HttpSolrClient.Builder(hosts).build();
        }
        return null;
    }
}