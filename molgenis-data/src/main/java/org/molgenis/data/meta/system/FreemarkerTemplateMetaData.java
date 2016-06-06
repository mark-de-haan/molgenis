package org.molgenis.data.meta.system;

import static org.molgenis.MolgenisFieldTypes.SCRIPT;
import static org.molgenis.data.meta.EntityMetaData.AttributeRole.ROLE_ID;
import static org.molgenis.data.meta.EntityMetaData.AttributeRole.ROLE_LABEL;
import static org.molgenis.data.meta.Package.PACKAGE_SEPARATOR;
import static org.molgenis.data.meta.RootSystemPackage.PACKAGE_SYSTEM;

import org.molgenis.data.meta.SystemEntityMetaData;
import org.springframework.stereotype.Component;

@Component
public class FreemarkerTemplateMetaData extends SystemEntityMetaData
{
	private static final String SIMPLE_NAME = "FreemarkerTemplate";
	public static final String FREEMARKER_TEMPLATE = PACKAGE_SYSTEM + PACKAGE_SEPARATOR + SIMPLE_NAME;

	public static final String ID = "id";
	public static final String NAME = "Name";
	public static final String VALUE = "Value";

	FreemarkerTemplateMetaData()
	{
		super(SIMPLE_NAME, PACKAGE_SYSTEM);
	}

	@Override
	public void init()
	{
		addAttribute(ID, ROLE_ID).setAuto(true).setVisible(false)
				.setDescription("automatically generated internal id, only for internal use.");
		addAttribute(NAME, ROLE_LABEL).setDescription("Name of the entity").setNillable(false).setUnique(true);
		addAttribute(VALUE).setDataType(SCRIPT).setNillable(false).setDescription("");
	}
}
