package org.daisy.pipeline.braille.pef.impl;

import java.util.Collection;
import java.util.Iterator;

import org.daisy.braille.api.factory.FactoryProperties;
import org.daisy.braille.api.table.TableProvider;
import org.daisy.common.spi.ServiceLoader;

import org.junit.Test;
import org.junit.Assert;

// only testing without OSGi because we need access to private package
public class BrailleUtilsTableCatalogTest {
	
	@Test
	public void listAllTables() {
		BrailleUtilsTableCatalog catalog = new BrailleUtilsTableCatalog();
		Iterator<TableProvider> providers = ServiceLoader.load(TableProvider.class).iterator();
		while (providers.hasNext()) catalog.addTableProvider(providers.next());
		catalog.list();
		Collection<FactoryProperties> allTables = catalog.list();
		// for (FactoryProperties t : allTables)
		// 	System.err.println(t.getIdentifier() + ": " + t.getDisplayName());
		Assert.assertEquals(25, allTables.size());
	}
}
