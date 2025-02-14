import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;

import javax.inject.Inject;

import org.daisy.braille.api.table.BrailleConverter;
import org.daisy.braille.api.table.Table;
import org.daisy.braille.api.table.TableCatalogService;

import static org.daisy.common.file.URLs.asURI;
import org.daisy.pipeline.braille.common.BrailleTranslator.FromStyledTextToBraille;
import org.daisy.pipeline.braille.common.BrailleTranslator.LineBreakingFromStyledText;
import org.daisy.pipeline.braille.common.BrailleTranslator.LineIterator;
import org.daisy.pipeline.braille.common.CSSStyledText;
import org.daisy.pipeline.braille.common.Hyphenator;
import org.daisy.pipeline.braille.common.HyphenatorProvider;
import org.daisy.pipeline.braille.common.Provider;
import org.daisy.pipeline.braille.common.TransformProvider;
import static org.daisy.pipeline.braille.common.Provider.util.dispatch;
import static org.daisy.pipeline.braille.common.Query.util.query;
import static org.daisy.pipeline.braille.common.util.Files.asFile;

import org.daisy.pipeline.braille.liblouis.LiblouisHyphenator;
import org.daisy.pipeline.braille.liblouis.LiblouisTable;
import org.daisy.pipeline.braille.liblouis.LiblouisTableResolver;
import org.daisy.pipeline.braille.liblouis.LiblouisTranslator;

import org.daisy.pipeline.junit.AbstractTest;

import static org.daisy.pipeline.pax.exam.Options.mavenBundle;
import static org.daisy.pipeline.pax.exam.Options.thisPlatform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.ComparisonFailure;
import org.junit.Test;

import org.ops4j.pax.exam.Configuration;
import static org.ops4j.pax.exam.CoreOptions.bundle;
import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.options;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.UrlProvisionOption;
import org.ops4j.pax.exam.ProbeBuilder;
import org.ops4j.pax.exam.TestProbeBuilder;
import org.ops4j.pax.exam.util.PathUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LiblouisCoreTest extends AbstractTest {
	
	@Inject
	public LiblouisTranslator.Provider provider;
	
	@Inject
	public LiblouisHyphenator.Provider hyphenatorProvider;
	
	@Inject
	public LiblouisTableResolver resolver;
	
	@Inject
	public TableCatalogService tableCatalog;
	
	private static final Logger messageBus = LoggerFactory.getLogger("JOB_MESSAGES");
	
	@Override
	protected String[] testDependencies() {
		return new String[] {
			brailleModule("libhyphen-utils"),
			brailleModule("common-utils"),
			brailleModule("css-utils"),
			brailleModule("pef-utils"),
			pipelineModule("file-utils"),
			pipelineModule("fileset-utils"),
			pipelineModule("common-utils"),
			"org.liblouis:liblouis-java:?",
			"org.daisy.braille:braille-utils.api:?",
			"org.daisy.pipeline:calabash-adapter:?",
		};
	}
	
	@ProbeBuilder
	public TestProbeBuilder probeConfiguration(TestProbeBuilder probe) {
		probe.setHeader("Bundle-Name", "test-module");
		// FIXME: can not delete this yet because it can not be generated with maven-bundle-plugin
		probe.setHeader("Service-Component", "OSGI-INF/mock-hyphenator-provider.xml,"
		                                   + "OSGI-INF/dispatching-table-provider.xml,"
		                                   + "OSGI-INF/table-path.xml");
		return probe;
	}
	
	@Override @Configuration
	public Option[] config() {
		return options(
			// FIXME: BrailleUtils needs older version of jing
			mavenBundle("org.daisy.libs:jing:20120724.0.0"),
			thisBundle(thisPlatform()),
			composite(super.config()));
	}
	
	@Test
	public void testResolveTableFile() {
		assertEquals("foobar.uti", asFile(resolver.resolve(asURI("foobar.uti"))).getName());
	}
	
	@Test
	public void testResolveTable() {
		assertEquals("foobar.uti", (resolver.resolveLiblouisTable(new LiblouisTable("foobar.uti"), null)[0]).getName());
	}
	
	@Test
	public void testGetTranslatorFromQuery1() {
		provider.withContext(messageBus).get(query("(locale:foo)")).iterator().next();
	}
	
	@Test
	public void testGetTranslatorFromQuery2() {
		provider.withContext(messageBus).get(query("(table:'foobar.uti')")).iterator().next();
	}
	
	@Test
	public void testGetTranslatorFromQuery3() {
		provider.withContext(messageBus).get(query("(locale:foo_BA)")).iterator().next();
	}
	
	@Test(expected=NoSuchElementException.class)
	public void testGetTranslatorFromQuery4() {
		provider.withContext(messageBus).get(query("(locale:bar)")).iterator().next();
	}
	
	@Test
	public void testTranslate() {
		assertEquals(braille("⠋⠕⠕⠃⠁⠗"),
		             provider.withContext(messageBus)
		                     .get(query("(table:'foobar.uti')")).iterator().next()
		                     .fromStyledTextToBraille().transform(text("foobar")));
	}
	
	@Test
	public void testTranslateStyled() {
		assertArrayEquals(new String[]{"⠨⠋⠕⠕⠃⠁⠗"},
		                  provider.withContext(messageBus)
		                          .get(query("(table:'foobar.uti,ital.cti')")).iterator().next()
		                          .fromTypeformedTextToBraille().transform(new String[]{"foobar"}, new String[]{"italic"}));
	}
	
	@Test
	public void testTextTransformUncontracted() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(locale:foo)(contraction:full)(output:ascii)")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(braille("fu ", "foo", " fu"),
		             translator.transform(styledText("foo ", "",
		                                             "foo",  "text-transform:uncontracted",
		                                             " foo", "")));
	}
	
	@Test
	public void testTranslateSegments() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.uti')")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(braille("⠋⠕⠕","⠃⠁⠗"),
		             translator.transform(text("foo","bar")));
		assertEquals(braille("⠋⠕⠕","","⠃⠁⠗"),
		             translator.transform(text("foo","","bar")));
	}
	
	@Test
	public void testTranslateStyledSegments() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.uti,ital.cti')")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(braille("⠋⠕⠕ ", "⠨⠃⠁⠗", " ⠃⠁⠵"),
		             translator.transform(styledText("foo ", "",
		                                             "bar",  "text-transform:-louis-ital",
		                                             // this doesn't work anymore because the print properties
		                                             // text-decoration, font-weight and color are not supported by
		                                             // org.daisy.braille.css.SimpleInlineStyle
		                                             " baz", "font-style: italic;"
		                                  )));
	}
	
	@Test
	public void testTranslateSegmentsFuzzy() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.ctb')")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(braille("⠋⠥","⠃⠁⠗"),
		             translator.transform(text("foo","bar")));
		assertEquals(braille("⠋⠥","⠃⠁⠗"),
		             translator.transform(text("fo","obar")));
		assertEquals(braille("⠋⠥\u00AD","⠃⠁⠗"),
		             translator.transform(text("fo","o\u00ADbar")));
		assertEquals(braille("⠋⠥","","⠃⠁⠗"),
		             translator.transform(text("fo","","obar")));
	}
	
	@Test
	public void testUnicodeNormalization() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(locale:foo)(contraction:no)")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(braille("⠷"), translator.transform(text("á")));
		assertEquals(braille("⠷"), translator.transform(text("\u0061\u0301")));
	}
	
	@Test
	public void testUndefinedChar() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(locale:foo)(contraction:full)(dots-for-undefined-char:'⣀')")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(braille("⣀"), translator.transform(text("€")));
	}
	
	@Test
	public void testHyphenate() {
		assertEquals("foo\u00ADbar",
		             hyphenatorProvider.withContext(messageBus)
		                 .get(query("(table:'foobar.uti,foobar.dic')")).iterator().next()
		                 .asFullHyphenator()
		                 .transform("foobar"));
	}
	
	@Test
	public void testHyphenateCompoundWord() {
		assertEquals("foo-\u200Bbar",
		             hyphenatorProvider.withContext(messageBus)
		                 .get(query("(table:'foobar.uti,foobar.dic')")).iterator().next()
		                 .asFullHyphenator()
		                 .transform("foo-bar"));
	}
	
	@Test
	public void testManualWordBreak() {
		assertEquals("foo\u00ADbar foo\u00ADbar foob\u00ADar",
		             hyphenatorProvider.withContext(messageBus)
		                 .get(query("(table:'foobar.uti,foobar.dic')")).iterator().next()
		                 .asFullHyphenator()
		                 .transform("foobar foo\u00ADbar foob\u00ADar"));
		assertEquals(braille("⠋⠕⠕\u00AD⠃⠁⠗ ⠋⠕⠕\u00AD⠃⠁⠗ ⠋⠕⠕⠃\u00AD⠁⠗"),
		             provider.withContext(messageBus)
		                     .get(query("(table:'foobar.uti,foobar.dic')")).iterator().next()
		                     .fromStyledTextToBraille()
		                     .transform(styledText("foobar foo\u00ADbar foob\u00ADar", "hyphens:auto")));
		assertEquals(
			"⠋⠕⠕⠤\n" +
			"⠃⠁⠗\n" +
			"⠋⠕⠕⠤\n" +
			"⠃⠁⠗\n" +
			"⠋⠕⠕⠃⠤\n" +
			"⠁⠗",
			fillLines(
				provider.withContext(messageBus)
		                .get(query("(table:'foobar.uti,foobar.dic')")).iterator().next()
				        .lineBreakingFromStyledText()
				        .transform(styledText("foobar foo\u00ADbar foob\u00ADar", "hyphens:auto")),
				5));
	}
	
	@Test
	public void testTranslateAndHyphenateNonStandard() {
		LineBreakingFromStyledText translator = provider.withContext(messageBus)
		                                                .get(query("(table:'foobar.ctb')(hyphenator:mock)(output:ascii)")).iterator().next()
		                                                .lineBreakingFromStyledText();
		assertEquals(
			"fu\n" +
			"bar\n" +
			"z",
			fillLines(translator.transform(styledText("foobarz", "hyphens:auto")), 3));
		assertEquals(
			"fub⠤\n" +
			"barz",
			fillLines(translator.transform(styledText("foobarz", "hyphens:auto")), 4));
		assertEquals(
			"fub⠤\n" +
			"barz",
			fillLines(translator.transform(styledText("foobarz", "hyphens:auto")), 5));
		assertEquals(
			"fubarz",
			fillLines(translator.transform(styledText("foobarz", "hyphens:auto")), 6));
	}
	
	@Test
	public void testTranslateAndHyphenateSomeSegments() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.uti,foobar.dic')")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(braille("⠋⠕⠕\u00AD⠃⠁⠗ ","⠋⠕⠕⠃⠁⠗"),
		             translator.transform(styledText("foobar ", "hyphens:auto",
		                                             "foobar",  "hyphens:none")));
	}
	
	@Test
	public void testWhiteSpaceProcessing() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.uti')")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(braille("⠋⠕⠕    ⠃⠁⠗ ⠃⠁⠵"),
		             translator.transform(text("foo    bar\nbaz")));
		assertEquals(braille("⠋⠕⠕    ⠃⠁⠗\n⠃⠁⠵"),
		             translator.transform(styledText("foo    bar\nbaz", "white-space:pre-wrap")));
		assertEquals(braille("",
		                     "⠋⠕⠕    ⠃⠁⠗\n\u00AD",
		                     "",
		                     "⠃⠁⠵"),
		             translator.transform(styledText("",             "",
		                                             "foo    bar\n", "white-space:pre-wrap",
		                                             "\u00AD",       "",
		                                             "baz",          "")));
		assertEquals(braille("\n"),
		             translator.transform(styledText("\n", "white-space:pre-line")));
	}
	
	@Test
	public void testWhiteSpaceLost() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.uti,delete-ws.utb')")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(braille("",
		                     "⠋⠕⠕⠃⠁⠗\u00AD",
		                     "",
		                     "⠃⠁⠵"),
		             translator.transform(styledText("",             "",
		                                             "foo    bar\n", "white-space:pre-wrap",
		                                             "\u00AD",       "",
		                                             "baz",          "")));
	}
	
	@Test
	public void testSegmentationPreservedDespiteSpacesCollapsed() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.uti,squash-ws.utb')(output:ascii)")).iterator().next()
		                                             .fromStyledTextToBraille();
		int n = 10000;
		String[] textSegments = new String[n]; {
			textSegments[0] = "foo";
			for (int i = 1; i < n - 1; i++)
				textSegments[i] = " ";
			textSegments[n - 1] = "bar"; }
		String[] brailleSegments = new String[n]; {
			brailleSegments[0] = "foo";
			brailleSegments[1] = " ";
			for (int i = 2; i < n - 1; i++)
				brailleSegments[i] = "";
			brailleSegments[n - 1] = "bar"; }
		assertEquals(braille(brailleSegments),
		             translator.transform(text(textSegments)));
	}
	
	@Test
	public void testTranslateWithLetterSpacingAndPunctuations() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.uti')(output:ascii)")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(
			braille("f o o b a r."),
			translator.transform(styledText("foobar.", "letter-spacing:1")));
		assertEquals(
			braille("f  o  o  b  a  r."),
			translator.transform(styledText("foobar.", "letter-spacing:2")));
	}
	
	@Test
	public void testTranslateWithLetterSpacingAndHyphenation() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.uti,foobar.dic')(output:ascii)")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(
			braille("f o o\u00AD b a r"),
			translator.transform(styledText("foobar", "letter-spacing:1; hyphens:auto")));
	}
	
	@Test
	public void testTranslateWithLetterSpacingAndContractions() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.ctb')(output:ascii)")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(
			braille("fu b a r"),
			translator.transform(styledText("foobar", "letter-spacing:1")));
		assertEquals(
			braille("fu  b  a  r"),
			translator.transform(styledText("foobar", "letter-spacing:2")));
	}
	
	@Test
	public void testTranslateWithLetterSpacingAndContractionsFuzzy() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'foobar.ctb')(output:ascii)")).iterator().next()
		                                             .fromStyledTextToBraille();
		assertEquals(braille("fu ","b a r"),
		             translator.transform(styledText("foo", "letter-spacing:1",
		                                             "bar", "letter-spacing:1")));
		assertEquals(braille("fu ","b a r"),
		             translator.transform(styledText("fo",   "letter-spacing:1",
		                                             "obar", "letter-spacing:1")));
		assertEquals(braille("fu\u00AD ","b a r"),
		             translator.transform(styledText("fo",         "letter-spacing:1",
		                                             "o\u00ADbar", "letter-spacing:1")));
		assertEquals(braille("fu ","","b a r"),
		             translator.transform(styledText("fo",   "letter-spacing:1",
		                                             "",     "letter-spacing:1",
		                                             "obar", "letter-spacing:1")));
	}
	
	@Test
	public void testTranslateWithWordSpacing() {
		LineBreakingFromStyledText translator = provider.withContext(messageBus)
		                                                .get(query("(table:'foobar.uti')(output:ascii)")).iterator().next()
		                                                .lineBreakingFromStyledText();
		assertEquals(
			"foo⠀⠀bar",
			translator.transform(styledText("foo bar", "word-spacing:2")).getTranslatedRemainder());
		assertEquals(
			"foo⠀⠀⠀bar",
			translator.transform(styledText("foo bar", "word-spacing:3")).getTranslatedRemainder());
	}

	@Test
	public void testTranslateWithWhiteSpaceProcessingAndWordSpacing() {
		LineBreakingFromStyledText translator = provider.withContext(messageBus)
		                                                .get(query("(table:'foobar.uti')(output:ascii)")).iterator().next()
		                                                .lineBreakingFromStyledText();
		// space in input, two spaces in output
		assertEquals(
			"foo⠀⠀bar",
			translator.transform(styledText("foo bar", "word-spacing:2")).getTranslatedRemainder());
		// two spaces in input, two spaces in output
		assertEquals(
			"foo⠀⠀bar",
			translator.transform(styledText("foo  bar", "word-spacing:2")).getTranslatedRemainder());
		// newline + tab in input, two spaces in output
		assertEquals(
			"foo⠀⠀bar",
			translator.transform(styledText("foo\n	bar", "word-spacing:2")).getTranslatedRemainder());
		// no-break space in input, space in output
		assertEquals(
			"foo⠀bar",
			translator.transform(styledText("foo bar", "word-spacing:2")).getTranslatedRemainder());
		// no-break space + space in input, three spaces in output
		assertEquals(
			"foo⠀⠀⠀bar",
			translator.transform(styledText("foo  bar", "word-spacing:2")).getTranslatedRemainder());
		// zero-width space in input, no space in output
		assertEquals(
			"foobar",
			translator.transform(styledText("foo​bar", "word-spacing:2")).getTranslatedRemainder());
	}
	
	@Test
	public void testTranslateWithLetterSpacing() {
		LineBreakingFromStyledText translator = provider.withContext(messageBus)
		                                                .get(query("(table:'foobar.uti')(output:ascii)")).iterator().next()
		                                                .lineBreakingFromStyledText();
		assertEquals(
			"f⠀o⠀o⠀b⠀a⠀r⠀⠀⠀q⠀u⠀u⠀x⠀⠀⠀#abcdef",
			translator.transform(styledText("foobar quux 123456", "letter-spacing:1; word-spacing:3")).getTranslatedRemainder());
		assertEquals(
			"f⠀⠀o⠀⠀o⠀⠀b⠀⠀a⠀⠀r⠀⠀⠀⠀⠀q⠀⠀u⠀⠀u⠀⠀x⠀⠀⠀⠀⠀#abcdef",
			translator.transform(styledText("foobar quux 123456", "letter-spacing:2; word-spacing:5")).getTranslatedRemainder());
	}

	@Test
	public void testTranslateWithLetterSpacingAndWordSpacing() {
		LineBreakingFromStyledText translator = provider.withContext(messageBus)
		                                                .get(query("(table:'foobar.uti')(output:ascii)")).iterator().next()
		                                                .lineBreakingFromStyledText();
		assertEquals(
			"f⠀o⠀o⠀b⠀a⠀r⠀⠀q⠀u⠀u⠀x⠀⠀#abcdef",
			translator.transform(styledText("foobar quux 123456", "letter-spacing:1; word-spacing:2")).getTranslatedRemainder());
		assertEquals(
			"f⠀o⠀o⠀b⠀a⠀r⠀⠀⠀q⠀u⠀u⠀x⠀⠀⠀#abcdef",
			translator.transform(styledText("foobar quux 123456", "letter-spacing:1; word-spacing:3")).getTranslatedRemainder());
		assertEquals(
			"f⠀⠀o⠀⠀o⠀⠀b⠀⠀a⠀⠀r⠀⠀⠀⠀q⠀⠀u⠀⠀u⠀⠀x⠀⠀⠀⠀#abcdef",
			translator.transform(styledText("foobar quux 123456", "letter-spacing:2; word-spacing:4")).getTranslatedRemainder());
		assertEquals(
			"f⠀⠀o⠀⠀o⠀⠀b⠀⠀a⠀⠀r⠀⠀⠀⠀⠀q⠀⠀u⠀⠀u⠀⠀x⠀⠀⠀⠀⠀#abcdef",
			translator.transform(styledText("foobar quux 123456", "letter-spacing:2; word-spacing:5")).getTranslatedRemainder());
	}

	@Test
	public void testTranslateWithWordSpacingAndLineBreaking() {
		LineBreakingFromStyledText translator = provider.withContext(messageBus)
		                                                .get(query("(table:'foobar.uti')(output:ascii)")).iterator().next()
		                                                .lineBreakingFromStyledText();
		assertEquals(
			//                   |<- 20
			"foobar⠀⠀foobar\n" +
			"foobar",
			fillLines(translator.transform(styledText("foobar foobar foobar", "word-spacing:2")), 20));
		assertEquals(
			//                   |<- 20
			"f⠀o⠀o⠀b⠀a⠀r\n" +
			"f⠀o⠀o⠀b⠀a⠀r\n" +
			"f⠀o⠀o⠀b⠀a⠀r",
			fillLines(translator.transform(styledText("foobar foobar foobar", "letter-spacing:1; word-spacing:3")), 20));
		assertEquals(
			//                        |<- 25
			"f⠀o⠀o⠀-⠀b⠀a⠀r⠀⠀⠀f⠀o⠀o⠀-\n" +
			"b⠀a⠀r⠀⠀⠀f⠀o⠀o⠀-⠀b⠀a⠀r",
			fillLines(translator.transform(styledText("foo-​bar foo-​bar foo-​bar", "letter-spacing:1; word-spacing:3")), 25)); // words are split up using hyphen + zwsp
		assertEquals(
			//                   |<- 20
			"f⠀o⠀o⠀b⠀a⠀r⠀⠀⠀f⠀o⠀o⠤\n" +
			"b⠀a⠀r⠀⠀⠀f⠀o⠀o⠀b⠀a⠀r",
			fillLines(translator.transform(styledText("foo­bar foo­bar foo­bar", "letter-spacing:1; word-spacing:3")), 20)); // words are split up using shy
	}

	@Test
	public void testTranslateWithPreservedLineBreaks() {
		LineBreakingFromStyledText translator = provider.withContext(messageBus)
		                                                .get(query("(table:'foobar.uti')(output:ascii)")).iterator().next()
		                                                .lineBreakingFromStyledText();
		assertEquals(
			//                   |<- 20
			"foobar\n" +
			"quux",
			fillLines(translator.transform(styledText("foobar quux", "word-spacing:2")), 20)); // words are split up using U+2028
		assertEquals(
			//                   |<- 20
			"norf\n" +
			"quux\n" +
			"foobar\n" +
			"xyzzy",
			fillLines(translator.transform(styledText("norf quux foobar xyzzy", "word-spacing:2")), 20)); // words are split up using U+2028
	}
	
	@Inject
	public DispatchingTableProvider tableProvider;
	
	@Test
	public void testDisplayTableProvider() {
		
		// (liblouis-table: ...)
		Table table = tableProvider.get(query("(liblouis-table:'foobar.dis')")).iterator().next();
		BrailleConverter converter = table.newBrailleConverter();
		assertEquals("⠋⠕⠕⠀⠃⠁⠗", converter.toBraille("foo bar"));
		assertEquals("foo bar", converter.toText("⠋⠕⠕⠀⠃⠁⠗"));
		
		//  (locale: ...)
		table = tableProvider.get(query("(locale:foo)")).iterator().next();
		converter = table.newBrailleConverter();
		assertEquals("⠋⠕⠕⠀⠃⠁⠗", converter.toBraille("foo bar"));
		assertEquals("foo bar", converter.toText("⠋⠕⠕⠀⠃⠁⠗"));
		
		// (id: ...)
		String id = table.getIdentifier();
		assertEquals(table, tableProvider.get(query("(id:'" + id + "')")).iterator().next());
		// assertEquals(table, tableCatalog.newTable(id));
	}
	
	@Test//(expected=ComparisonFailure.class)
	public void testTranslatorStatefulBug() {
		FromStyledTextToBraille translator = provider.withContext(messageBus)
		                                             .get(query("(table:'stateful.utb')")).iterator().next()
		                                             .fromStyledTextToBraille();
		String p = translator.transform(text("p")).iterator().next();
		//assertEquals("⠰⠏", p);
		translator.transform(text("xx"));
		// this is expected to fail:
		assertEquals(p, translator.transform(text("p")).iterator().next());
	}
	
	private Iterable<CSSStyledText> styledText(String... textAndStyle) {
		List<CSSStyledText> styledText = new ArrayList<CSSStyledText>();
		String text = null;
		boolean textSet = false;
		for (String s : textAndStyle) {
			if (textSet)
				styledText.add(new CSSStyledText(text, s));
			else
				text = s;
			textSet = !textSet; }
		if (textSet)
			throw new RuntimeException();
		return styledText;
	}
	
	private Iterable<CSSStyledText> text(String... text) {
		List<CSSStyledText> styledText = new ArrayList<CSSStyledText>();
		for (String t : text)
			styledText.add(new CSSStyledText(t, ""));
		return styledText;
	}
	
	private Iterable<String> braille(String... text) {
		return Arrays.asList(text);
	}
	
	private static String fillLines(LineIterator lines, int width) {
		StringBuilder sb = new StringBuilder();
		while (lines.hasNext()) {
			sb.append(lines.nextTranslatedRow(width, true));
			if (lines.hasNext())
				sb.append('\n'); }
		return sb.toString();
	}
	
	private static UrlProvisionOption thisBundle(String classifier) {
		File classes = new File(PathUtils.getBaseDir() + "/target/classes");
		Properties dependencies = new Properties(); {
			try {
				dependencies.load(new FileInputStream(new File(classes, "META-INF/maven/dependencies.properties"))); }
			catch (IOException e) {
				throw new RuntimeException(e); }
		}
		String artifactId = dependencies.getProperty("artifactId");
		String version = dependencies.getProperty("version");
		// assuming JAR is named ${artifactId}-${version}.jar
		return bundle("reference:" +
		              new File(PathUtils.getBaseDir() + "/target/" + artifactId + "-" + version + "-" + classifier + ".jar").toURI());
	}
}
