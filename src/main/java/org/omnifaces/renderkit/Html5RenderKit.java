/*
 * Copyright 2017 OmniFaces
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.omnifaces.renderkit;

import static org.omnifaces.util.Components.getCurrentComponent;
import static org.omnifaces.util.Faces.getInitParameter;
import static org.omnifaces.util.Utils.isEmpty;
import static org.omnifaces.util.Utils.isOneInstanceOf;
import static org.omnifaces.util.Utils.unmodifiableSet;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.faces.component.UICommand;
import javax.faces.component.UIComponent;
import javax.faces.component.UIForm;
import javax.faces.component.UIInput;
import javax.faces.component.UISelectBoolean;
import javax.faces.component.UISelectMany;
import javax.faces.component.UISelectOne;
import javax.faces.component.html.HtmlCommandButton;
import javax.faces.component.html.HtmlInputSecret;
import javax.faces.component.html.HtmlInputText;
import javax.faces.component.html.HtmlInputTextarea;
import javax.faces.context.ResponseWriter;
import javax.faces.context.ResponseWriterWrapper;
import javax.faces.render.RenderKit;
import javax.faces.render.RenderKitWrapper;

/**
 * <p>
 * This HTML5 render kit adds support for HTML5 specific attributes which are unsupported by the JSF {@link UIForm},
 * {@link UIInput} and {@link UICommand} components. So far in JSF 2.0 and 2.1 only the <code>autocomplete</code>
 * attribute is supported in {@link UIInput} components. All other attributes are by design ignored by the JSF standard
 * HTML render kit. This HTML5 render kit supports the following HTML5 specific attributes:
 * <ul>
 * <li>{@link UIForm}: <ul><li><code>autocomplete</code></li></ul></li>
 * <li>{@link UISelectBoolean}, {@link UISelectOne} and {@link UISelectMany}: <ul><li><code>autofocus</code></li></ul></li>
 * <li>{@link HtmlInputText}: <ul><li><code>type</code> (supported values are <code>text</code> (default), <code>search</code>, <code>email</code>, <code>url</code>, <code>tel</code>, <code>range</code>, <code>number</code> and <code>date</code>)</li><li><code>autofocus</code></li><li><code>list</code></li><li><code>pattern</code></li><li><code>placeholder</code></li><li><code>spellcheck</code></li><li><code>min</code></li><li><code>max</code></li><li><code>step</code></li></ul>(the latter three are only supported on <code>type</code> of <code>range</code>, <code>number</code> and <code>date</code>)</li>
 * <li>{@link HtmlInputTextarea}: <ul><li><code>autofocus</code></li><li><code>maxlength</code></li><li><code>placeholder</code></li><li><code>spellcheck</code></li><li><code>wrap</code></li></ul></li>
 * <li>{@link HtmlInputSecret}: <ul><li><code>autofocus</code></li><li><code>pattern</code></li><li><code>placeholder</code></li></ul></li>
 * <li>{@link HtmlCommandButton}: <ul><li><code>autofocus</code></li></ul></li>
 * </ul>
 * <p>
 * Note: the <code>list</code> attribute expects a <code>&lt;datalist&gt;</code> element which needs to be coded in
 * "plain vanilla" HTML (and is currently, July 2014, only supported in IE 10, Firefox 4, Chrome 20 and Opera 11). See
 * also <a href="http://www.html5tutorial.info/html5-datalist.php">HTML5 tutorial</a>.
 *
 * <h3>Installation</h3>
 * <p>
 * To use the HTML5 render kit, register it as follows in <code>faces-config.xml</code>:
 * <pre>
 * &lt;factory&gt;
 *     &lt;render-kit-factory&gt;org.omnifaces.renderkit.Html5RenderKitFactory&lt;/render-kit-factory&gt;
 * &lt;/factory&gt;
 * </pre>
 *
 * <h3>Configuration</h3>
 * <p>
 * You can also configure additional passthrough attributes via the
 * {@value org.omnifaces.renderkit.Html5RenderKit#PARAM_NAME_PASSTHROUGH_ATTRIBUTES} context parameter in
 * <code>web.xml</code>, wherein the passthrough attributes are been specified in semicolon-separated
 * <code>com.example.SomeComponent=attr1,attr2,attr3</code> key=value pairs. The key represents the fully qualified
 * name of a class whose {@link Class#isInstance(Object)} must return <code>true</code> for the particular component
 * and the value represents the commaseparated string of names of passthrough attributes. Here's an example:
 * <pre>
 * &lt;context-param&gt;
 *     &lt;param-name&gt;org.omnifaces.HTML5_RENDER_KIT_PASSTHROUGH_ATTRIBUTES&lt;/param-name&gt;
 *     &lt;param-value&gt;
 *         javax.faces.component.UIInput=x-webkit-speech,x-webkit-grammar;
 *         javax.faces.component.UIComponent=contenteditable,draggable
 *       &lt;/param-value&gt;
 * &lt;/context-param&gt;
 * </pre>
 *
 * <h3>Mojarra f:ajax bug</h3>
 * <p>
 * Note that <code>&lt;f:ajax&gt;</code> of Mojarra 2.0.0-2.1.13 explicitly checks for
 * <code>&lt;input type="text"&gt;</code> and ignores other types while preparing request parameters for ajax submit,
 * resulting in <code>null</code> values in managed bean after an ajax submit. This has been reported as
 * <a href="http://java.net/jira/browse/JAVASERVERFACES-2532">Mojarra issue 2532</a> and is fixed in Mojarra 2.1.14.
 * This problem is thus completely unrelated to <code>Html5RenderKit</code>.
 *
 * <h3>JSF 2.2 notice</h3>
 * <p>
 * Noted should be that JSF 2.2 will support defining custom attributes directly in the view via the new
 * <code>http://xmlns.jcp.org/jsf/passthrough</code> namespace or the <code>&lt;f:passThroughAttribute&gt;</code> tag.
 * <pre>
 * &lt;html ... xmlns:p="http://xmlns.jcp.org/jsf/passthrough"&gt;
 * ...
 * &lt;h:inputText ... p:autofocus="true" /&gt;
 * </pre>
 * <em>(you may want to use <code>a</code> instead of <code>p</code> as namespace prefix to avoid clash with PrimeFaces
 * default namespace)</em>
 * <p>
 * Or:
 * <pre>
 * &lt;h:inputText ...&gt;
 *     &lt;f:passThroughAttribute name="autofocus" value="true" /&gt;
 * &lt;/h:inputText&gt;
 * </pre>
 *
 * <h3>Deprecation</h3>
 * <p>
 * As per OmniFaces 2.2 (actually technically already since OmniFaces 2.0), this HTML5 render kit is deprecated. Users
 * are encouraged to migrate to new JSF 2.2 support for passthrough attributes as described above. The HTML5 render kit
 * is scheduled to be removed in a future OmniFaces 3.0 (for JSF 2.3).
 *
 * @author Bauke Scholtz
 * @since 1.1
 * @deprecated
 */
@Deprecated
public class Html5RenderKit extends RenderKitWrapper {

	// Constants ------------------------------------------------------------------------------------------------------

	/** The context parameter name to specify additional passthrough attributes. */
	public static final String PARAM_NAME_PASSTHROUGH_ATTRIBUTES =
		"org.omnifaces.HTML5_RENDER_KIT_PASSTHROUGH_ATTRIBUTES";

	private static final Set<String> HTML5_UIFORM_ATTRIBUTES = unmodifiableSet(
		"autocomplete"
		// "novalidate" attribute is not useable in a JSF form.
	);

	private static final Set<String> HTML5_SELECT_ATTRIBUTES = unmodifiableSet(
		"autofocus"
		// "form" attribute is not useable in a JSF form.
	);

	private static final Set<String> HTML5_TEXTAREA_ATTRIBUTES = unmodifiableSet(
		"autofocus", "maxlength", "placeholder", "spellcheck", "wrap"
		// "form" attribute is not useable in a JSF form.
		// "required" attribute can't be used as it would override JSF default "required" attribute behaviour.
	);

	private static final Set<String> HTML5_INPUT_ATTRIBUTES = unmodifiableSet(
		"autofocus", "list", "pattern", "placeholder", "spellcheck"
		// "form*" attributes are not useable in a JSF form.
		// "multiple" attribute is only applicable on <input type="email"> and <input type="file"> and can't be
		// decoded by standard HtmlInputText.
		// "required" attribute can't be used as it would override JSF default "required" attribute behaviour.
	);

	private static final Set<String> HTML5_INPUT_PASSWORD_ATTRIBUTES = unmodifiableSet(
		"autofocus", "pattern", "placeholder"
		// "form*" attributes are not useable in a JSF form.
		// "required" attribute can't be used as it would override JSF default "required" attribute behaviour.
	);

	private static final Set<String> HTML5_INPUT_RANGE_ATTRIBUTES = unmodifiableSet(
		"max", "min", "step"
	);

	private static final Set<String> HTML5_INPUT_RANGE_TYPES = unmodifiableSet(
		"range", "number", "date"
	);

	private static final Set<String> HTML5_INPUT_TYPES = unmodifiableSet(
		"text", "search", "email", "url", "tel", HTML5_INPUT_RANGE_TYPES
	);

	private static final Set<String> HTML5_BUTTON_ATTRIBUTES = unmodifiableSet(
		"autofocus"
		// "form" attribute is not useable in a JSF form.
	);

	private static final String ERROR_INVALID_INIT_PARAM =
		"Context parameter '" + PARAM_NAME_PASSTHROUGH_ATTRIBUTES + "' is in invalid syntax.";
	private static final String ERROR_INVALID_INIT_PARAM_CLASS =
		"Context parameter '" + PARAM_NAME_PASSTHROUGH_ATTRIBUTES + "'"
			+ " references a class which is not found in runtime classpath: '%s'";
	private static final String ERROR_UNSUPPORTED_HTML5_INPUT_TYPE =
		"HtmlInputText type '%s' is not supported. Supported types are " + HTML5_INPUT_TYPES + ".";

	// Properties -----------------------------------------------------------------------------------------------------

	private RenderKit wrapped;
	private Map<Class<UIComponent>, Set<String>> passthroughAttributes;

	// Constructors ---------------------------------------------------------------------------------------------------

	/**
	 * Construct a new HTML5 render kit around the given wrapped render kit.
	 * @param wrapped The wrapped render kit.
	 */
	public Html5RenderKit(RenderKit wrapped) {
		this.wrapped = wrapped;
		passthroughAttributes = initPassthroughAttributes();
	}

	// Actions --------------------------------------------------------------------------------------------------------

	/**
	 * Returns a new HTML5 response writer which in turn wraps the default response writer.
	 */
	@Override
	public ResponseWriter createResponseWriter(Writer writer, String contentTypeList, String characterEncoding) {
		return new Html5ResponseWriter(super.createResponseWriter(writer, contentTypeList, characterEncoding));
	}

	@Override
	public RenderKit getWrapped() {
		return wrapped;
	}

	// Helpers --------------------------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private static Map<Class<UIComponent>, Set<String>> initPassthroughAttributes() {
		String passthroughAttributesParam = getInitParameter(PARAM_NAME_PASSTHROUGH_ATTRIBUTES);

		if (isEmpty(passthroughAttributesParam)) {
			return null;
		}

		Map<Class<UIComponent>, Set<String>> passthroughAttributes = new HashMap<>();

		for (String passthroughAttribute : passthroughAttributesParam.split("\\s*;\\s*")) {
			String[] classAndAttributeNames = passthroughAttribute.split("\\s*=\\s*", 2);

			if (classAndAttributeNames.length != 2) {
				throw new IllegalArgumentException(ERROR_INVALID_INIT_PARAM);
			}

			String className = classAndAttributeNames[0];
			Object[] attributeNames = classAndAttributeNames[1].split("\\s*,\\s*");
			Set<String> attributeNameSet = unmodifiableSet(attributeNames);

			try {
				passthroughAttributes.put((Class<UIComponent>) Class.forName(className), attributeNameSet);
			}
			catch (ClassNotFoundException e) {
				throw new IllegalArgumentException(String.format(ERROR_INVALID_INIT_PARAM_CLASS, className), e);
			}
		}

		return passthroughAttributes;
	}

	// Nested classes -------------------------------------------------------------------------------------------------

	/**
	 * This HTML5 response writer does all the job.
	 * @author Bauke Scholtz
	 */
	class Html5ResponseWriter extends ResponseWriterWrapper {

		// Properties -------------------------------------------------------------------------------------------------

		private ResponseWriter wrapped;

		// Constructors -----------------------------------------------------------------------------------------------

		public Html5ResponseWriter(ResponseWriter wrapped) {
			this.wrapped = wrapped;
		}

		// Actions ----------------------------------------------------------------------------------------------------

		@Override
		public ResponseWriter cloneWithWriter(Writer writer) {
			return new Html5ResponseWriter(super.cloneWithWriter(writer));
		}

		/**
		 * An override which checks if the given component is an instance of {@link UIForm} or {@link UIInput} and then
		 * write HTML5 attributes which are explicitly been set by the developer.
		 */
		@Override
		public void startElement(String name, UIComponent component) throws IOException {
			super.startElement(name, component);

			if (component == null) {
				return; // Either the renderer is broken, or it's plain text/html.
			}

			if (component instanceof UIForm && "form".equals(name)) {
				writeHtml5AttributesIfNecessary(component.getAttributes(), HTML5_UIFORM_ATTRIBUTES);
			}
			else if (component instanceof UIInput) {
				writeHtml5AttributesIfNecessary((UIInput) component, name);
			}
			else if (component instanceof UICommand && "input".equals(name)) {
				writeHtml5AttributesIfNecessary(component.getAttributes(), HTML5_BUTTON_ATTRIBUTES);
			}

			if (passthroughAttributes != null) {
				for (Entry<Class<UIComponent>, Set<String>> entry : passthroughAttributes.entrySet()) {
					if (entry.getKey().isInstance(component)) {
						writeHtml5AttributesIfNecessary(component.getAttributes(), entry.getValue());
					}
				}
			}
		}

		/**
		 * An override which checks if an attribute of <code>type="text"</code> is been written by an {@link UIInput}
		 * component and if so then check if the <code>type</code> attribute isn't been explicitly set by the developer
		 * and if so then write it.
		 * @throws IllegalArgumentException When the <code>type</code> attribute is not supported.
		 */
		@Override
		public void writeAttribute(String name, Object value, String property) throws IOException {
			if ("type".equals(name) && "text".equals(value)) {
				UIComponent component = getCurrentComponent();

				if (component instanceof HtmlInputText) {
					Object type = component.getAttributes().get("type");

					if (type != null) {
						if (HTML5_INPUT_TYPES.contains(type)) {
							super.writeAttribute(name, type, null);
							return;
						}
						else {
							throw new IllegalArgumentException(
								String.format(ERROR_UNSUPPORTED_HTML5_INPUT_TYPE, type));
						}
					}
				}
			}

			super.writeAttribute(name, value, property);
		}

		@Override
		public ResponseWriter getWrapped() {
			return wrapped;
		}

		// Helpers ----------------------------------------------------------------------------------------------------

		private void writeHtml5AttributesIfNecessary(UIInput component, String name) throws IOException {
			if (isInput(component, name)) {
				Map<String, Object> attributes = component.getAttributes();
				writeHtml5AttributesIfNecessary(attributes, HTML5_INPUT_ATTRIBUTES);

				if (HTML5_INPUT_RANGE_TYPES.contains(attributes.get("type"))) {
					writeHtml5AttributesIfNecessary(attributes, HTML5_INPUT_RANGE_ATTRIBUTES);
				}
			}
			else if (isInputPassword(component, name)) {
				writeHtml5AttributesIfNecessary(component.getAttributes(), HTML5_INPUT_PASSWORD_ATTRIBUTES);
			}
			else if (isTextarea(component, name)) {
				writeHtml5AttributesIfNecessary(component.getAttributes(), HTML5_TEXTAREA_ATTRIBUTES);
			}
			else if (isSelect(component, name)) {
				writeHtml5AttributesIfNecessary(component.getAttributes(), HTML5_SELECT_ATTRIBUTES);
			}
		}

		private void writeHtml5AttributesIfNecessary(Map<String, Object> attributes, Set<String> names) throws IOException {
			for (String name : names) {
				Object value = attributes.get(name);

				if (value != null) {
					super.writeAttribute(name, value, null);
				}
			}
		}

		private boolean isInput(UIInput component, String name) {
			return component instanceof HtmlInputText && "input".equals(name);
		}

		private boolean isInputPassword(UIInput component, String name) {
			return component instanceof HtmlInputSecret && "input".equals(name);
		}

		private boolean isTextarea(UIInput component, String name) {
			return component instanceof HtmlInputTextarea && "textarea".equals(name);
		}

		private boolean isSelect(UIInput component, String name) {
			return isOneInstanceOf(component.getClass(), UISelectBoolean.class, UISelectOne.class, UISelectMany.class)
				&& ("input".equals(name) || "select".equals(name));
		}
	}

}