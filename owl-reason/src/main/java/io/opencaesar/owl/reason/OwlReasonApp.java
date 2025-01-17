/**
 * Copyright 2019 California Institute of Technology ("Caltech").
 * U.S. Government sponsorship acknowledged.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.opencaesar.owl.reason;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.ontology.Ontology;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.vocabulary.RDFS;
import org.apache.log4j.Appender;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import openllet.core.KnowledgeBase;
import openllet.jena.ModelExtractor;
import openllet.jena.ModelExtractor.StatementType;
import openllet.jena.vocabulary.OWL2;
import openllet.owlapi.OpenlletReasoner;
import openllet.owlapi.OpenlletReasonerFactory;
import openllet.owlapi.explanation.PelletExplanation;

public class OwlReasonApp {
  
	private static final String CONSISTENCY = "Consistency";
	private static final String SATISFIABILITY = "Satisfiability";

	private Options options = new Options();
	
	private class Options {
		@Parameter(
			names = { "--catalog-path", "-c"},
			description = "path to the input OWL catalog (Required)",
			validateWith = CatalogPath.class,
			required = true,
			order = 1)
		String catalogPath;
		
		@Parameter(
			names = { "--input-ontology-iri", "-i"},
			description = "iri of input OWL ontology (Required)",
			required = true,
			order = 2)
		String inputOntologyIri;

		@Parameter(
			names = {"--spec", "-s"},
			description = "output-ontology-iri= list of entailment statement types separarted by | (Required)",
			converter = SpecConverter.class,
			required = true,
			order = 3)
		List<Spec> specs = new ArrayList<Spec>();
		
		@Parameter(
			names = {"--report-path", "-r"},
			description = "path to a report file in Junit XML format ",
			validateWith = ReportPathValidator.class,
			required = true,
			order = 4)
		String reportPath;

		@Parameter(
			names = {"--input-file-extension", "-if"},
			description = "input file extension (owl by default, options: owl, rdf, xml, rj, ttl, n3, nt, trig, nq, trix, jsonld, fss)",
			validateWith = FileExtensionValidator.class,
			required = false,
			order = 5)
	    private List<String> inputFileExtensions = new ArrayList<>();
	    {
	    	inputFileExtensions.add("owl");
	    }
		
		@Parameter(
			names = {"--output-file-extension", "-of"},
			description = "output file extension (ttl by default, options: owl, rdf, xml, rj, ttl, n3, nt, trig, nq, trix, jsonld, fss)",
			validateWith = OutputFileExtensionValidator.class,
			required = false,
			order = 6)
	    private String outputFileExtension = "ttl";

		@Parameter(
			names = {"--remove-unsats", "-ru"},
			description = "remove entailments due to unsatisfiability",
			required = false,
			order = 7)
		boolean removeUnsats = true;
		
		@Parameter(
			names = {"--remove-backbone", "-rb"},
			description = "remove axioms on the backhone from entailments",
			required = false,
			order = 8)
		boolean removeBackbone = true;

		@Parameter(
			names = {"--backbone-iri", "-b"},
			description = "iri of backbone ontology",
			required = false,
			order = 9)
		String backboneIri = "http://opencaesar.io/oml";
				
		@Parameter(
			names = {"--indent", "-n"},
			description = "indent of the JUnit XML elements",
			required = false,
			order = 10)
		int indent = 2;

		@Parameter(
			names = {"--debug", "-d"},
			description = "Shows debug logging statements",
			order = 11)
		private boolean debug;

		@Parameter(
			names = {"--help", "-h"},
			description = "Displays summary of options",
			help = true,
			order =12)
		private boolean help;
	}
		
	private static class Spec {
		String outputOntologyIri;
		EnumSet<StatementType> statementTypes;
	}

	private static class Result {
		public String name;
		public String message;
		public String explanation;
	}

	private final static Logger LOGGER = Logger.getLogger(OwlReasonApp.class);
	{
        DOMConfigurator.configure(ClassLoader.getSystemClassLoader().getResource("log4j.xml"));
	}

	public static void main(final String... args) throws Exception {
		final OwlReasonApp app = new OwlReasonApp();
		final JCommander builder = JCommander.newBuilder().addObject(app.options).build();
		builder.parse(args);
		if (app.options.help) {
			builder.usage();
			return;
		}
		if (app.options.debug) {
			final Appender appender = LogManager.getRootLogger().getAppender("stdout");
			((AppenderSkeleton) appender).setThreshold(Level.DEBUG);
		}
		app.run(args);
	}

	public void run(final String... args) throws Exception {
		LOGGER.info("=================================================================");
		LOGGER.info("                        S T A R T");
		LOGGER.info("                     OWL Reason " + getAppVersion());
		LOGGER.info("=================================================================");
	    	    	    
	    // Create ontology manager.
	    
	    LOGGER.info("create ontology manager");
	    final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
	    if (manager == null) {
	    	throw new RuntimeException("couldn't create owl ontology manager");
	    }
	    LOGGER.debug("add location mappers");
		manager.getIRIMappers().add((new XMLCatalogIRIMapper(new File(options.catalogPath), options.inputFileExtensions)));

	    // Get Pellete reasoner factory.

	    LOGGER.info("create pellet reasoner factory");
		final OpenlletReasonerFactory reasonerFactory = OpenlletReasonerFactory.getInstance();

	    // Create FunctionalSyntaxDocumentFormat.

	    FunctionalSyntaxDocumentFormat functionalSyntaxFormat = new FunctionalSyntaxDocumentFormat();

	    // Check the input ontology
    	check(manager, reasonerFactory, functionalSyntaxFormat, options.inputOntologyIri);

    	LOGGER.info("=================================================================");
		LOGGER.info("                          E N D");
		LOGGER.info("=================================================================");
	}
	
	public void check(final OWLOntologyManager manager, OpenlletReasonerFactory reasonerFactory, FunctionalSyntaxDocumentFormat functionalSyntaxFormat, String inputOntologyIri) throws Exception {
	    // Load input ontology.
	    
	    LOGGER.info("load ontology "+inputOntologyIri);
	    final OWLOntology inputOntology = manager.loadOntology(IRI.create(inputOntologyIri));
	    if (inputOntology == null) {
	    	throw new RuntimeException("couldn't load ontology");
	    }
	    
	    // Create Pellet reasoner.

	    LOGGER.info("create pellet reasoner for "+inputOntologyIri);
	    PelletExplanation.setup();
	    OpenlletReasoner reasoner = reasonerFactory.createReasoner(inputOntology);
	    if (reasoner == null) {
	    	throw new RuntimeException("couldn't create reasoner");
	    }
	    
	    // Create PelletExplanation.
	    
	    LOGGER.info("create explanation for "+inputOntologyIri);
	    PelletExplanation explanation = new PelletExplanation(reasoner);
	    	    
	    // Create knowledge base.

	    LOGGER.info("create knowledge base and extractor");
	    KnowledgeBase kb = reasoner.getKB();
	    if (kb == null) {
	    	throw new RuntimeException("couldn't get knowledge base");
	    }

	    // Check for consistency and satisfiability
		
		Map<String, List<Result>> allResults = new LinkedHashMap<String, List<Result>>();
		allResults.put(CONSISTENCY, checkConsistency(inputOntologyIri, reasoner, explanation, functionalSyntaxFormat));
		boolean isConsistent = !allResults.get(CONSISTENCY).stream().filter(r -> r.explanation != null).findFirst().isPresent();
		boolean isSatisfiable = false;
		if (isConsistent) {
	    	allResults.put(SATISFIABILITY, checkSatisfiability(inputOntologyIri, reasoner, explanation, functionalSyntaxFormat));
	    	isSatisfiable = !allResults.get(SATISFIABILITY).stream().filter(r -> r.explanation != null).findFirst().isPresent();
	    }
		writeResults(inputOntologyIri, allResults, options.indent);
	    
		// Check Results
		
		if (!isConsistent) {
			LOGGER.error("Check "+options.reportPath+" for more details.");
			throw new ReasoningException("Ontology is inconsistent. Check "+options.reportPath+" for more details.");
	    }
		if (!isSatisfiable) {
			LOGGER.error("Check "+options.reportPath+" for more details.");
			throw new ReasoningException("Ontology has insatisfiabilities. Check "+options.reportPath+" for more details.");
	    }
	    
	    // Iterate over specs and extract entailments.

	    for (Spec spec: options.specs) {
	      String outputOntologyIri = spec.outputOntologyIri;
	      EnumSet<StatementType> statementTypes = spec.statementTypes;
	      extractAndSaveEntailments(kb, inputOntologyIri, outputOntologyIri, statementTypes, manager);
	    }
	}

	private List<Result> checkConsistency(String ontologyIri, OpenlletReasoner reasoner, PelletExplanation explanation, FunctionalSyntaxDocumentFormat functionalSyntaxFormat) throws Exception {
    	LOGGER.info("test consistency on "+ontologyIri);
    	List<Result> results = new ArrayList<Result>();

		boolean success = reasoner.isConsistent();
    	if (success) {
    		LOGGER.info("Ontology "+ontologyIri+" is consistent");
    	} else {
    		LOGGER.error("Ontology "+ontologyIri+" is inconsistent");
    	}
    	
    	Result result = new Result();
    	result.name = ontologyIri;
        if (!success) {
        	//TODO: consider using explanation.getInconsistencyExplanations()
        	Set<OWLAxiom> axioms = explanation.getInconsistencyExplanation();
        	result.message = reasoner.getKB().getExplanation();
        	result.explanation = createExplanationOntology(axioms, functionalSyntaxFormat);
        }
	    results.add(result);
    
	    return results;
	}

	private List<Result> checkSatisfiability(String ontologyIri, OpenlletReasoner reasoner, PelletExplanation explanation, FunctionalSyntaxDocumentFormat functionalSyntaxFormat) throws Exception {
    	LOGGER.info("test satisfiability on "+ontologyIri);
    	List<Result> results = new ArrayList<Result>();
    	
		Set<OWLClass> allClasses = reasoner.getRootOntology().classesInSignature(Imports.INCLUDED).collect(Collectors.toSet());
		if (allClasses == null) {
			throw new RuntimeException("can't get all classes");
		}
		
		int numOfClasses = allClasses.size();   	
    	LOGGER.info(numOfClasses+" total classes");

    	int count = 0;
    	int numOfUnsat = 0;
    	for (OWLClass klass : allClasses) {
    		if (options.removeBackbone && klass.getIRI().getIRIString().startsWith(options.backboneIri))
    			continue;
    		
    		String className = klass.getIRI().getIRIString();
    	    LOGGER.info(className+" "+ ++count+" of "+numOfClasses);

    	    boolean success = reasoner.isSatisfiable(klass);
    	    LOGGER.info("class "+className+" is "+(success?"":"un")+"satisfiable");

    	    Result result = new Result();
    	    results.add(result);
    	    result.name = className;
    	    
    	    if (!success) {
    	    	result.message = "class "+className+" is insatisfiable";
    	    	result.explanation = createExplanationOntology(explanation.getUnsatisfiableExplanation(klass), functionalSyntaxFormat);
    	    	numOfUnsat += 1;
    	    }
    	}
    	if (numOfUnsat > 0) {
    		LOGGER.error("Ontology "+ontologyIri+" has "+numOfUnsat+" insatisfiabilities");
    	}

    	return results;
	}

	private String createExplanationOntology(Set<OWLAxiom> axioms, FunctionalSyntaxDocumentFormat format) throws Exception {
	    OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
	    if (manager == null ) {
	    	throw new RuntimeException("couldn't create owl ontology manager");
	    }
	    OWLOntology ontology = manager.createOntology(axioms);
	    if (ontology == null) {
	    	throw new RuntimeException("couldn't create ontology for explanation");
	    }
		StringDocumentTarget target = new StringDocumentTarget();
	    manager.saveOntology(ontology, format, target);
	    return target.toString();
	}

	private void writeResults(String ontologyIri, Map<String, List<Result>> allResults, int indent) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
	    Document doc = db.newDocument();
		Element tss = doc.createElement("testsuites");
		tss.setAttribute("name", ontologyIri);
		doc.appendChild(tss);
		
		allResults.forEach((test, results) -> {
	    	Element ts = doc.createElement("testsuite");
	      	ts.setAttribute("name", test);
			tss.appendChild(ts);
			results.forEach(result -> {
		    	Element tc = doc.createElement("testcase");
		        tc.setAttribute("name", result.name);
		    	ts.appendChild(tc);
		        if (result.explanation != null) {
		        	Element fl = doc.createElement("failure");
		        	tc.appendChild(fl);
		        	fl.setAttribute("message", result.message);
		        	CDATASection cdoc = doc.createCDATASection("\n"+result.message+"\n\n"+result.explanation+"\n");
		    		fl.appendChild(cdoc);
	  	        }
	    	});
	    });
	    	    
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", ""+indent);
        DOMSource source = new DOMSource(doc);
        File reportFile = new File(options.reportPath);
        try (FileOutputStream stream = new FileOutputStream(reportFile)) {
	        StreamResult console = new StreamResult(stream);
	        transformer.transform(source, console);
        }
	}
	
	private void extractAndSaveEntailments(KnowledgeBase kb, String inputOntologyIri, String outputOntologyIri, EnumSet<StatementType> statementTypes, OWLOntologyManager manager) throws Exception {
		// Create extractor.
	  
		LOGGER.info("create extractor for "+statementTypes);
		ModelExtractor extractor = new ModelExtractor(kb);

		// Extract entailments

		LOGGER.info("extract entailments for "+statementTypes);
		Model entailments = extractEntailments(extractor, statementTypes);

		// Remove trivial axioms involving owl:Thing and owl:Nothing.

		LOGGER.info("remove trivial entailments for "+statementTypes);
		entailments = removeTrivial(entailments, options.removeUnsats);

		// Remove backbone entailments.

		LOGGER.info("remove backbone entailments for "+statementTypes);
		if (options.removeBackbone) {
			entailments = removeBackbone(entailments, options.backboneIri);
		}

		// Create Jena ontology model for results.
		  
		LOGGER.info("create jena ontology model for "+statementTypes);
		OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, entailments);
		  
		// Create Jena ontology from model.
		  
		LOGGER.info("create jena ontology "+outputOntologyIri+" from model");
		Ontology outputOntology = model.createOntology(outputOntologyIri);
		outputOntology.addImport(ResourceFactory.createResource(inputOntologyIri));
		outputOntology.addComment("Generated by Owl Reason "+ getAppVersion(), null);
		outputOntology.addVersionInfo(""+Instant.now().getEpochSecond());

		// Create an empty OWLAPI Ontology to get the ontology document IRI

		LOGGER.info("get output filename from location mapping");
		OWLOntology empty = manager.createOntology(IRI.create(outputOntologyIri+"."+options.outputFileExtension));
		String filename = URI.create(manager.getOntologyDocumentIRI(empty).toString()).getPath();
		manager.removeOntology(empty);
		  
		// Open output stream.

		LOGGER.info("open output stream "+filename);
		File outputFile = new File(filename);
		outputFile.getParentFile().mkdirs();
		FileOutputStream outputFileStream = new FileOutputStream(outputFile);
		  
		// Serialize Jena ontology model to output stream.
		  
		LOGGER.info("serialize "+entailments.size()+" entailments to "+filename);
		Lang lang = RDFLanguages.fileExtToLang(options.outputFileExtension);
		if (lang == null) {
			String message = "No RDF writer available for extension: " + options.outputFileExtension;
			LOGGER.error(message);
			throw new IllegalArgumentException(message);
		}
		model.write(outputFileStream, lang.getName());
		LOGGER.info("finished serializing "+filename);
	}
	
	private Model extractEntailments(ModelExtractor extractor, EnumSet<StatementType> types) {
	    // Extract entailments.
	    extractor.setSelector(types);
	    Model result = extractor.extractModel();
	    LOGGER.info("extracted "+result.size()+" entailed axioms");
	    return result;
	}
	
	/*
	 *  Remove trivial entailments involving owl:Thing, owl:Nothing, owl:topObjectProperty, owl:topDataProperty
	 */
	private Model removeTrivial(Model entailments, boolean removeUnsats) {
		StmtIterator iterator = entailments.listStatements();
		List<Statement> trivial = new ArrayList<Statement>();
	    while (iterator.hasNext()) {
	    	Statement statement = iterator.next();
	    	Resource subject = statement.getSubject();
	    	Property predicate = statement.getPredicate();
	    	RDFNode object = statement.getObject();
	    	if ((predicate.equals(RDFS.subClassOf) && (subject.equals(OWL2.Nothing) || (removeUnsats && object.equals(OWL2.Nothing)) || object.equals(OWL2.Thing))) ||
	   	        (predicate.equals(RDFS.subPropertyOf) && (object.equals(OWL2.topObjectProperty) || object.equals(OWL2.topDataProperty)) || (subject.equals(OWL2.bottomObjectProperty) || subject.equals(OWL2.bottomDataProperty)))) {
	   	        trivial.add(statement);
	   	    }
	    }
	    entailments.remove(trivial);
	    LOGGER.info("removed "+trivial.size()+" trivial axioms");
	    return entailments;
	}
	
	/*
	 * Remove entailments involving backbone items.
	 */
	private Model removeBackbone(Model entailments, String pattern) {
		StmtIterator iterator = entailments.listStatements();
		List<Statement> backbone = new ArrayList<Statement>();
	    while (iterator.hasNext()) {
	    	Statement statement = iterator.next();
	    	Property predicate = statement.getPredicate();
	    	RDFNode object = statement.getObject();
	    	if (object instanceof Resource) {
	    		String objectIri = ((Resource)object).getURI();
		        if ((predicate.equals(RDFS.subClassOf) || predicate.equals(RDFS.subPropertyOf)) && objectIri.startsWith(pattern)) {
		        	backbone.add(statement);
		        }
	    	}
	    }
	    entailments.remove(backbone);
	    LOGGER.info("removed "+backbone.size()+" backbone axioms");
	    return entailments;
	}
	
	/**
	 * Get application version id from properties file.
	 * 
	 * @return version string from build.properties or UNKNOWN
	 */
	private String getAppVersion() throws Exception {
    	var version = this.getClass().getPackage().getImplementationVersion();
    	return (version != null) ? version : "<SNAPSHOT>";
    }

	public static class CatalogPath implements IParameterValidator {
		@Override
		public void validate(final String name, final String value) throws ParameterException {
			File file = new File(value);
			if (!file.getName().endsWith("catalog.xml")) {
				throw new ParameterException("Parameter " + name + " should be a valid OWL catalog path");
			}
		}
	}
	
	public static class SpecConverter implements IStringConverter<Spec> {
		@Override
		public Spec convert(String value) {
			String[] s = value.split("=");
			Spec spec = new Spec();
			spec.outputOntologyIri = s[0].trim();
			spec.statementTypes = EnumSet.noneOf(StatementType.class);
			for (String type : s[1].trim().split("\\|")) {
				type = type.trim();
				StatementType st = StatementType.valueOf(type);
		        if (st != null) {
		        	spec.statementTypes.add(st);
		        } else {
		  	    	throw new ParameterException("invalid entailment type "+type);
		        }
			}
			return spec;
		}
		
	}

	// Must be in sync with io.opencaesar.oml2owl.Oml2OwlApp.FileExtensionValidator#extensions
	public static HashSet<String> extensions = new HashSet<>();

	static {
		extensions.add("fss");
		extensions.add("owl");
		extensions.add("rdf");
		extensions.add("xml");
		extensions.add("n3");
		extensions.add("ttl");
		extensions.add("rj");
		extensions.add("nt");
		extensions.add("jsonld");
		extensions.add("trig");
		extensions.add("trix");
		extensions.add("nq");
	}

	public static class FileExtensionValidator implements IParameterValidator {
		@Override
		public void validate(final String name, final String value) throws ParameterException {
			if (!extensions.contains(value)) {
				throw new ParameterException("Parameter " + name + " should be a valid extension, got: " + value +
						" recognized extensions are: " +
						extensions.stream().reduce( (x,y) -> x + " " + y) );
			}
		}
		
	}

	public static class OutputFileExtensionValidator implements IParameterValidator {
		@Override
		public void validate(final String name, final String value) throws ParameterException {
			Lang lang = RDFLanguages.fileExtToLang(value);
			if (lang == null) {
				throw new ParameterException("Parameter " + name + " should be a valid RDF output extension, got: " + value +
						" recognized RDF extensions are: rdf, owl, xml, ttl, n3, nt, jsonld, rj, trig, trix, nq, rt");
			}
		}

	}
	public static class ReportPathValidator implements IParameterValidator {
		@Override
		public void validate(final String name, final String value) throws ParameterException {
			File file = new File(value);
			if (!file.getName().endsWith(".xml")) {
				throw new ParameterException("Parameter " + name + " should be a valid XML path");
			}
			File parentFile = file.getParentFile();
			if (parentFile != null && !parentFile.exists()) {
				parentFile.mkdirs();
			}
	  	}
	}
	
	@SuppressWarnings("serial")
	private class ReasoningException extends Exception {
		public ReasoningException(String s) {
			super(s);
		}
	}
}