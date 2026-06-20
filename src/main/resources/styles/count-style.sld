<?xml version="1.0" encoding="UTF-8"?>
<!--
  count_style：人口数量样式。
  GeoServerRestClient.ensureStyle 上传该 SLD 后，basic/basic_all/scene/finance_app 可作为默认样式引用。
  渲染字段统一使用 SQL View 返回的 total_num。
-->
<sld:StyledLayerDescriptor xmlns:sld="http://www.opengis.net/sld"
                           xmlns:ogc="http://www.opengis.net/ogc"
                           xmlns:xlink="http://www.w3.org/1999/xlink"
                           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                           version="1.0.0">
    <sld:NamedLayer>
        <sld:Name>count_style</sld:Name>
        <sld:UserStyle>
            <sld:Title>人口数量样式</sld:Title>
            <sld:FeatureTypeStyle>
                <sld:Rule>
                    <sld:Name>high</sld:Name>
                    <ogc:Filter>
                        <ogc:PropertyIsGreaterThanOrEqualTo>
                            <ogc:PropertyName>total_num</ogc:PropertyName>
                            <ogc:Literal>1000</ogc:Literal>
                        </ogc:PropertyIsGreaterThanOrEqualTo>
                    </ogc:Filter>
                    <sld:PolygonSymbolizer>
                        <sld:Fill>
                            <sld:CssParameter name="fill">#006837</sld:CssParameter>
                            <sld:CssParameter name="fill-opacity">0.72</sld:CssParameter>
                        </sld:Fill>
                        <sld:Stroke>
                            <sld:CssParameter name="stroke">#004529</sld:CssParameter>
                            <sld:CssParameter name="stroke-width">0.6</sld:CssParameter>
                        </sld:Stroke>
                    </sld:PolygonSymbolizer>
                </sld:Rule>
                <sld:Rule>
                    <sld:Name>medium</sld:Name>
                    <ogc:Filter>
                        <ogc:And>
                            <ogc:PropertyIsGreaterThanOrEqualTo>
                                <ogc:PropertyName>total_num</ogc:PropertyName>
                                <ogc:Literal>300</ogc:Literal>
                            </ogc:PropertyIsGreaterThanOrEqualTo>
                            <ogc:PropertyIsLessThan>
                                <ogc:PropertyName>total_num</ogc:PropertyName>
                                <ogc:Literal>1000</ogc:Literal>
                            </ogc:PropertyIsLessThan>
                        </ogc:And>
                    </ogc:Filter>
                    <sld:PolygonSymbolizer>
                        <sld:Fill>
                            <sld:CssParameter name="fill">#78C679</sld:CssParameter>
                            <sld:CssParameter name="fill-opacity">0.62</sld:CssParameter>
                        </sld:Fill>
                        <sld:Stroke>
                            <sld:CssParameter name="stroke">#238443</sld:CssParameter>
                            <sld:CssParameter name="stroke-width">0.5</sld:CssParameter>
                        </sld:Stroke>
                    </sld:PolygonSymbolizer>
                </sld:Rule>
                <sld:Rule>
                    <sld:Name>low</sld:Name>
                    <sld:PolygonSymbolizer>
                        <sld:Fill>
                            <sld:CssParameter name="fill">#D9F0A3</sld:CssParameter>
                            <sld:CssParameter name="fill-opacity">0.5</sld:CssParameter>
                        </sld:Fill>
                        <sld:Stroke>
                            <sld:CssParameter name="stroke">#78C679</sld:CssParameter>
                            <sld:CssParameter name="stroke-width">0.4</sld:CssParameter>
                        </sld:Stroke>
                    </sld:PolygonSymbolizer>
                </sld:Rule>
            </sld:FeatureTypeStyle>
        </sld:UserStyle>
    </sld:NamedLayer>
</sld:StyledLayerDescriptor>
