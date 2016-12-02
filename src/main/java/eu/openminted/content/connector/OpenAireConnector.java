package eu.openminted.content.connector;

import eu.openminted.content.openaire.OpenAireNamespaceContext;
import eu.openminted.registry.domain.Facet;
import eu.openminted.registry.domain.Value;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import javax.xml.validation.Validator;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

@Component
public class OpenAireConnector implements ContentConnector {
    private static Logger log = Logger.getLogger(OpenAireConnector.class.getName());
    private String schemaAddress;

    @Override
    public SearchResult search(Query query) {
        SearchResult searchResult = new SearchResult();

        try {
            Parser parser = new Parser();
            OpenAireSolrClient client = new OpenAireSolrClient();
            QueryResponse response = client.query(query);
            searchResult.setFrom((int) response.getResults().getStart());
            searchResult.setTo((int) response.getResults().getStart() + response.getResults().size());
            searchResult.setTotalHits((int) response.getResults().getNumFound());

            List<Facet> facets = new ArrayList<>();
            if (response.getFacetFields() != null) {
                for (FacetField facetField : response.getFacetFields()) {
                    Facet facet = new Facet();
                    facet.setLabel(facetField.getName());
                    facet.setField(facetField.getName());
                    List<Value> values = new ArrayList<>();
                    for (FacetField.Count count : facetField.getValues()) {
                        Value value = new Value();
                        value.setValue(count.getName());
                        value.setCount((int) count.getCount());
                        values.add(value);
                    }
                    facet.setValues(values);
                    facets.add(facet);
                }
            }

            searchResult.setFacets(facets);

            for (SolrDocument document : response.getResults()) {
                // TODO: The getFieldName to get the result should be given as input
                // There may be more than one fields, yet we care only for the result
                // It would be nice, if this field is the only field needed, to be set once
                // from a properties file.

                // TODO: xml validation for the initial queries is needed and yet the oaf xsd has issues
                // leaving xml validation for as feature in future commit

                String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + document.getFieldValue("__result").toString().replaceAll("\\[|\\]", "");
//                if (!schemaAddress.isEmpty()) {
//                    Validator validator = createValidator(schemaAddress);
//                    Source source = new StreamSource(xml);
//                    try {
//                        if (validator != null) {
//                            validator.validate(source);
//                            log.info(source.getSystemId() + " is valid");
//                        }
//                    } catch (SAXException e) {
//                        log.error(source.getSystemId() + " is NOT valid");
//                        log.error("Reason: " + e.getLocalizedMessage());
//                        continue;
//                    }
//                }

                parser.parse(new InputSource(new StringReader(xml)));
                searchResult.setPublications(new ArrayList<>());
                searchResult.getPublications().add(parser.getOMTDPublication());
            }
        } catch (Exception e) {
            log.error("OpenAireConnector.search", e);
        }
        return searchResult;
    }

    private Validator createValidator(String schemaFileUrl) throws MalformedURLException, SAXException {
        log.info("Waiting for XML Validator");
        URL schemaUrl = new URL(schemaFileUrl);
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = schemaFactory.newSchema(schemaUrl);
        return schema.newValidator();
    }

    @Override
    public InputStream downloadFullText(String s) {
        return null;
    }

    @Override
    public InputStream fetchMetadata(Query query) {
        OpenAireSolrClient client = new OpenAireSolrClient();
        PipedInputStream inputStream = new PipedInputStream();
        try {
            new Thread(()->
            {
                try {
                    client.fetchMetadata(query);
                } catch (IOException | SolrServerException | InterruptedException e) {
                    log.error("OpenAireConnector.fetchMetadata", e);
                }
            }).start();

            client.getPipedOutputStream().connect(inputStream);
        } catch (IOException e) {
            log.error("OpenAireConnector.fetchMetadata", e);
        }
        return inputStream;
    }

    @Override
    public String getSourceName() {
        return "OpenAIRE";
    }

    public String getSchemaAddress() {
        return schemaAddress;
    }

    public void setSchemaAddress(String schemaAddress) {
        this.schemaAddress = schemaAddress;
    }
}
