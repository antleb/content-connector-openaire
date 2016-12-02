package eu.openminted.content.connector;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.common.SolrDocument;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.PipedOutputStream;
import java.io.StringReader;

public class OpenAireStreamingResponseCallback extends StreamingResponseCallback {
    private static Logger log = Logger.getLogger(OpenAireConnector.class.getName());
    PipedOutputStream outputStream;
    String outputField;

    public OpenAireStreamingResponseCallback(PipedOutputStream out, String field) {
        outputStream = out;
        outputField = field;
    }

    @Override
    public void streamSolrDocument(SolrDocument solrDocument) {
        try {
            Parser parser = new Parser();
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + solrDocument.getFieldValue(outputField).toString()
                    .replaceAll("\\[|\\]", "");
            parser.parse(new InputSource(new StringReader(xml)));
            outputStream.write(parser.getOMTDPublication().getBytes());
        } catch (IOException | SAXException | JAXBException | ParserConfigurationException e) {
            log.error("OpenAireStreamingResponseCallback.streamSolrDocument", e);
        }
    }

    @Override
    public void streamDocListInfo(long l, long l1, Float aFloat) {
    }
}