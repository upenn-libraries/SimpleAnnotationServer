package uk.org.llgc.annotation.store;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

import com.github.jsonldjava.utils.JsonUtils;
import com.github.jsonldjava.core.JsonLdOptions;
import com.github.jsonldjava.core.JsonLdProcessor;
import com.github.jsonldjava.core.JsonLdError;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.StringWriter;
import java.io.File;

import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.Lang;

import com.hp.hpl.jena.rdf.model.Model;

import uk.org.llgc.annotation.store.encoders.Encoder;

public class AnnotationUtils {
	protected static Logger _logger = LogManager.getLogger(AnnotationUtils.class.getName()); 

	protected File _contextDir = null;
	protected Encoder _encoder = null;

	public AnnotationUtils(final File pContextDir, final Encoder pEncoder) {
		_contextDir = pContextDir;
		_encoder = pEncoder;
	}

	/**
	 * Convert a IIIF annotation list into a list of annotations that have fragement 
	 * identifiers
	 * @param InputStream the input stream to read to get the IIIF annotation list
	 */
	public List<Map<String,Object>> readAnnotationList(final InputStream pStream, final String pBaseURL) throws IOException {
		Map<String,Object> tAnnotationList = (Map<String,Object>)JsonUtils.fromInputStream(pStream);
		_logger.debug("Original untouched annotation:");
		_logger.debug(JsonUtils.toPrettyString(tAnnotationList));
		List<Map<String,Object>> tAnnotations = (List<Map<String,Object>>)tAnnotationList.get("resources");

		if (tAnnotationList.get("@id") == null) {
			_logger.debug(JsonUtils.toPrettyString(tAnnotationList));
			throw new IOException("Annotation list must have a @id at root");
		}
		String[] tListURI = ((String)tAnnotationList.get("@id")).split("/");
		String tBucketId = tListURI[tListURI.length - 1].replaceAll(".json","");
		int tAnnoCount = 0;
		for (Map<String, Object> tAnno : tAnnotations) {
			if (tAnno.get("@id") == null) {
				StringBuffer tBuff = new StringBuffer(pBaseURL);
				tBuff.append("/");
				tBuff.append(tBucketId);
				tBuff.append("/");
				tBuff.append(tAnnoCount++);
				tAnno.put("@id", tBuff.toString());

			}
			tAnno.put("@context", this.getContext()); // need to add context to each annotation fixes issue #18
			
			// do I need to change the format to html?
			((Map<String, Object>)tAnno.get("resource")).put("@type","dctypes:Text"); //requried for Mirador: js/src/annotations/osd-canvas-renderer.js:421:if (value["@type"] === "dctypes:Text") {
			((Map<String, Object>)tAnno.get("resource")).put("format","text/html");
			String tText = (String)((Map<String, Object>)tAnno.get("resource")).get("chars");
			((Map<String, Object>)tAnno.get("resource")).put("chars", "<p>" + tText + "</p>");

			// Not sure if this is strictly necessary:
			/*List<String> tMotivation = new ArrayList<String>();
			tMotivation.add("oa:commenting");
			tAnno.put("motivation", tMotivation); // replaces painting with commenting*/


			String[] tOnStr = ((String)tAnno.get("on")).split("#");

			Map<String,Object> tOnObj = new HashMap<String,Object>();
			tOnObj.put("@type", "oa:SpecificResource");
			tOnObj.put("full", tOnStr[0]);

			Map<String,Object> tSelector = new HashMap<String,Object>();
			tOnObj.put("selector", tSelector);
			tSelector.put("@type", "oa:FragmentSelector");
			tSelector.put("value", tOnStr[1]);

			tAnno.put("on", tOnObj);

			if (_encoder != null) {
				_encoder.encode(tAnno);
			}
		}
		return tAnnotations;
	}

	@SuppressWarnings("unchecked") 
	public Map<String, Object> readAnnotaion(final InputStream pStream, final String pBaseURL) throws IOException {
		Object tAnnotation = JsonUtils.fromInputStream(pStream);
		Map<String, Object> tRoot = (Map<String,Object>)tAnnotation;

		if (tRoot.get("@id") == null) { 
			String tID = pBaseURL + this.generateAnnoId();
			tRoot.put("@id", tID);
		}	
		// Change context to local for quick processing
		tRoot.put("@context", this.getContext());

		if (_encoder != null) {
			_encoder.encode(tRoot);
		}
		return tRoot;
	}

	protected String getContext() {
		return "file://" + new File(_contextDir, "iiif-2.0.json").getPath();
	}


	@SuppressWarnings("unchecked") 
	protected Map<String, Object> buildAnnotationListHead() {
		Map<String, Object> tRoot = (Map<String, Object>)new HashMap<String,Object>();
		tRoot.put("@context", "http://iiif.io/api/presentation/2/context.json");
		String tPageId = "tmp";
		tRoot.put("@id", "http://dams.llgc.org.uk/iiif/annotation/list/" + tPageId);// current URL TODO make better id and resolvable
		tRoot.put("@type", "sc:AnnotationList");
		List tResources = new ArrayList();
		tRoot.put("resources", tResources);

		return tRoot;
	}

	public Map<String,Object> createAnnotationList(final Model pAnnotation) throws IOException {
		List<Model> tTmpList = new ArrayList<Model>();
		tTmpList.add(pAnnotation);

		return createAnnotationList(tTmpList).get(0);
	}

	public List<Map<String,Object>> createAnnotationList(final List<Model> pAnnotations) throws IOException {
		final Object contextJson = JsonUtils.fromInputStream(new FileInputStream(new File(_contextDir, "annotation_frame.json")));
		((Map)contextJson).put("@context", this.getContext());

		final JsonLdOptions options = new JsonLdOptions();
		options.format = "application/jsonld";

		Map<String, Object> tRoot = this.buildAnnotationListHead();
		List tResources = (List)tRoot.get("resources");
		StringWriter tStringOut = null;
		for (Model tAnnotation : pAnnotations) {
			tStringOut = new StringWriter();
			RDFDataMgr.write(tStringOut, tAnnotation, Lang.JSONLD);

			try {
				Object framed = JsonLdProcessor.frame(JsonUtils.fromString(tStringOut.toString()), contextJson,  options);

				Map tJsonLd = (Map)((List)((Map)framed).get("@graph")).get(0);
				//tJsonLd.put("@context","http://iiif.io/api/presentation/2/context.json");
				//this.colapseFragement(tJsonLd);
				Map<String, Object> tOn = (Map<String, Object>)tJsonLd.get("on");
				// Check if this is a valid annotation
				// if it is valid it should have one source, one fragment selector
				if (((Map<String,Object>)tOn.get("selector")).get("value") instanceof List || tOn.get("source") instanceof List) {
					_logger.error("Annotation is broken " + tJsonLd.get("@id"));
				} else {
					if (_encoder != null) {
 						_encoder.decode(tJsonLd);
 					}
					tResources.add(tJsonLd);
				}	
			} catch (JsonLdError tExcpt) {
				_logger.error("Failed to generate Model " + tAnnotation.toString() + "  due to " + tExcpt);
				tExcpt.printStackTrace();
			}
		}

		return tResources;//tRoot;
	}

	// Need to move fragement into on
	protected void colapseFragement(final Map pAnnotationJson) {
		String tFragement = (String)((Map)((Map)pAnnotationJson.get("on")).get("selector")).get("value");
		String tTarget = (String)((Map)pAnnotationJson.get("on")).get("full");
		pAnnotationJson.put("on", tTarget + "#" + tFragement);
	}

	protected String generateAnnoId() {
		return "" + new java.util.Date().getTime();
	}
}
