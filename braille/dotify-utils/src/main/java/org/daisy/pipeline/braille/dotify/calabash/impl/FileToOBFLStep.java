package org.daisy.pipeline.braille.dotify.calabash.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.transform.stream.StreamSource;

import org.daisy.common.xproc.calabash.XProcStep;
import org.daisy.common.xproc.calabash.XProcStepProvider;

import org.daisy.streamline.api.identity.IdentityProviderService;
import org.daisy.streamline.api.media.AnnotatedFile;
import org.daisy.streamline.api.media.DefaultAnnotatedFile;
import org.daisy.streamline.api.tasks.InternalTask;
import org.daisy.streamline.api.tasks.TaskGroupFactory;
import org.daisy.streamline.api.tasks.TaskGroupInformation;
import org.daisy.streamline.api.tasks.TaskSystem;
import org.daisy.streamline.api.tasks.TaskSystemException;
import org.daisy.streamline.api.tasks.TaskSystemFactoryException;
import org.daisy.streamline.api.tasks.TaskSystemFactory;
import org.daisy.streamline.engine.TaskRunner;
import org.daisy.dotify.common.xml.XMLTools;
import org.daisy.dotify.common.xml.XMLToolsException;

import org.daisy.pipeline.braille.common.Query.Feature;
import static org.daisy.pipeline.braille.common.Query.util.query;
import static org.daisy.pipeline.braille.common.util.Files.asFile;

import org.osgi.framework.FrameworkUtil;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xmlcalabash.core.XProcException;
import com.xmlcalabash.core.XProcRuntime;
import com.xmlcalabash.io.ReadablePipe;
import com.xmlcalabash.io.WritablePipe;
import com.xmlcalabash.library.DefaultStep;
import com.xmlcalabash.model.RuntimeValue;
import com.xmlcalabash.runtime.XAtomicStep;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;

public class FileToOBFLStep extends DefaultStep implements XProcStep {
	private static final QName _source = new QName("source");
	
	private static final QName _locale = new QName("locale");
	private static final QName _format = new QName("format");
	private static final QName _dotifyOptions = new QName("dotify-options");
	
	private static final QName _template = new QName("template");
    private static final QName _rows = new QName("rows");
    private static final QName _cols = new QName("cols");
    private static final QName _innerMargin = new QName("inner-margin");
    private static final QName _outerMargin = new QName("outer-margin");
    private static final QName _rowgap = new QName("rowgap");
    private static final QName _splitterMax = new QName("splitterMax");
    private static final QName _identifier = new QName("identifier");
    
    private static final Logger logger = LoggerFactory.getLogger(FileToOBFLStep.class);
	
	private WritablePipe result = null;
	private final Map<String,String> parameters = new HashMap<>();
	
	private final TaskSystemFactory taskSystemFactory;
	private final TaskGroupFactory taskGroupFactory;
	private final IdentityProviderService identifyProvider;

	public FileToOBFLStep(XProcRuntime runtime,
	                      XAtomicStep step,
	                      TaskSystemFactory taskSystemFactory,
	                      TaskGroupFactory taskGroupFactory,
	                      IdentityProviderService identifyProvider) {
		super(runtime, step);
		this.taskSystemFactory = taskSystemFactory;
		this.taskGroupFactory = taskGroupFactory;
		this.identifyProvider = identifyProvider;
	}
	
	@Override
	public void setInput(String port, ReadablePipe pipe) {
		throw new XProcException("No input document allowed on port '" + port + "'");
	}
	
	@Override
	public void setOutput(String port, WritablePipe pipe) {
		result = pipe;
	}
	
	@Override
	public void setParameter(String port, QName name, RuntimeValue value) {
		if ("parameters".equals(port))
			setParameter(name, value);
		else
			throw new XProcException("No parameters allowed on port '" + port + "'");
	}
	
	@Override
	public void setParameter(QName name, RuntimeValue value) {
		if ("".equals(name.getNamespaceURI()))
			parameters.put(name.getLocalName(), value.getString());
	}
	
	@Override
	public void reset() {
		result.resetWriter();
	}
	
	@Override
	public void run() throws SaxonApiException {
		super.run();
		try {
			File inputFile = asFile(getOption(_source).getString());
			Map<String, Object> params = new HashMap<String, Object>();
			addOption(_template, params);
			addOption(_rows, params);
			addOption(_cols, params);
			addOption(_innerMargin, params);
			addOption(_outerMargin, params);
			addOption(_rowgap, params);
			addOption(_splitterMax, params);
			addOption(_identifier, params);
			
			params.putAll(parameters);
			
			RuntimeValue rv = getOption(_dotifyOptions);
			if (rv!=null) {
				for (Feature f : query(rv.getString())) {
					String key = f.getKey();
					Optional<String> val = f.getValue();
					//if there isn't a value, just repeat the key
					params.put(key, val.orElse(key));
				}
			}
			
			params.put("page-width", toInt(params.remove("cols"))+toInt(params.get("inner-margin"))+toInt(params.get("outer-margin")));
			params.put("page-height", toInt(params.remove("rows")));
			params.put("row-spacing", new BigDecimal(1+(toInt(params.remove("rowgap"))/4d)));
			
			String locale = getOption(_locale, Locale.getDefault().toString());
			String outputFormat = getOption(_format, "obfl");
			InputStream resultStream = convert(inputFile, outputFormat, locale, params);
			
			// Write result
			result.write(runtime.getProcessor().newDocumentBuilder().build(new StreamSource(resultStream)));
			resultStream.close();
		} catch (Exception e) {
			logger.error("dotify:file-to-obfl failed", e);
			throw new XProcException(step.getNode(), e);
		}
	}
	
	private static int toInt(Object v) {
		return v==null?0:Integer.parseInt(v.toString());
	}
	
	private void addOption(QName opt, Map<String, Object> params) {
		RuntimeValue o = getOption(opt);
		if (o!=null) {
			params.put(opt.getLocalName(), o.getString());
		}
	}
		
	private InputStream convert(File input, String outputFormat, String locale, Map<String, Object> params) throws TaskSystemFactoryException, TaskSystemException, IOException {
		
		// FIXME: see https://github.com/joeha480/dotify/issues/205
		AnnotatedFile ai = identifyProvider.identify(input);

		String inputFormat = getFormatString(ai);
		if (!supportsInputFormat(inputFormat, taskGroupFactory.listAll())) {
			logger.debug("No input factory for " + inputFormat);
			logger.info("Note, the following detection code has been deprected. In future versions, an exception will be thrown if this point is reached."
						+ " To avoid this, use the IdentifierFactory interface to implement a detector for the file type.");
			// attempt to detect a supported type
			try {
				if (XMLTools.isWellformedXML(ai.getFile())) {
					ai = DefaultAnnotatedFile.with(ai).extension("xml").build();
					inputFormat = ai.getExtension();
					logger.info("Input is well-formed xml."); }}
			catch (XMLToolsException e) {
				logger.info("File is not well-formed xml: " + ai.getFile(), e);
			}
		} else {
			logger.info("Found an input factory for " + inputFormat);
		}

		params.put("inputFormat", inputFormat);
		params.put("input", ai.getFile().getAbsolutePath());
		TaskSystem system = newTaskSystem(inputFormat, outputFormat, locale);
		List<InternalTask> tasks = system.compile(params);
		
		// Create a destination file
		File dest = File.createTempFile("file-to-obfl", ".tmp");
		dest.deleteOnExit();
		
		// Run tasks
		TaskRunner runner = TaskRunner.withName("dotify:file-to-obfl").build();
		runner.runTasks(ai, dest, tasks);
		
		// Return stream
		return new FileInputStream(dest);
	}
	
	private static boolean supportsInputFormat(String inputFormat, Set<TaskGroupInformation> specs) {
		for (TaskGroupInformation s : specs) {
			if (s.getInputFormat().equals(inputFormat)) {
				return true;
			}
		}
		return false;
	}
	
	private static String getFormatString(AnnotatedFile f) {
		// FIXME: see https://github.com/joeha480/dotify/issues/205

		if (f.getFormatName()!=null) {
			return f.getFormatName();
		} else if (f.getExtension()!=null) {
			return f.getExtension();
		} else if (f.getMediaType()!=null) {
			return f.getMediaType();
		} else {
			return null;
		}
	}

	private TaskSystem newTaskSystem(String inputFormat, String outputFormat, String locale) throws TaskSystemFactoryException {
		return taskSystemFactory.newTaskSystem(inputFormat, outputFormat, locale);
	}
	
	@Component(
		name = "dotify:file-to-obfl",
		service = { XProcStepProvider.class },
		property = { "type:String={http://code.google.com/p/dotify/}file-to-obfl" }
	)
	public static class Provider implements XProcStepProvider {
		
		@Override
		public XProcStep newStep(XProcRuntime runtime, XAtomicStep step) {
			return new FileToOBFLStep(runtime, step, taskSystemFactory, taskGroupFactory, identifyProvider);
		}
		
		private TaskSystemFactory taskSystemFactory = null;
		private TaskGroupFactory taskGroupFactory = null;
		private IdentityProviderService identifyProvider = null;
		
		@Reference(
			name = "TaskSystemFactory",
			service = TaskSystemFactory.class,
			cardinality = ReferenceCardinality.MANDATORY,
			policy = ReferencePolicy.STATIC
		)
		protected void bindTaskSystemFactory(TaskSystemFactory service) {
			if (!OSGiHelper.inOSGiContext())
				service.setCreatedWithSPI();
			taskSystemFactory = service;
		}
		
		@Reference(
			name = "TaskGroupFactory",
			service = TaskGroupFactory.class,
			cardinality = ReferenceCardinality.MANDATORY,
			policy = ReferencePolicy.STATIC
		)
		protected void bindTaskGroupFactory(TaskGroupFactory service) {
			if (!OSGiHelper.inOSGiContext())
				service.setCreatedWithSPI();
			taskGroupFactory = service;
		}
		
		@Reference(
			name = "IdentityProviderService",
			service = IdentityProviderService.class,
			cardinality = ReferenceCardinality.MANDATORY,
			policy = ReferencePolicy.STATIC
		)
		protected void bindIdentityProviderService(IdentityProviderService service) {
			identifyProvider = service;
		}
	}
	
	private static abstract class OSGiHelper {
		static boolean inOSGiContext() {
			try {
				return FrameworkUtil.getBundle(OSGiHelper.class) != null;
			} catch (NoClassDefFoundError e) {
				return false;
			}
		}
	}
}
