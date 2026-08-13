import { useEffect, useState, useCallback, useRef } from "react";
import {
    Card,
    Elevation,
    FormGroup,
    Button,
    MenuItem,
    NonIdealState,
    NonIdealStateIconSize,
    Spinner,
    SpinnerSize
} from "@blueprintjs/core";
import { type ItemRenderer, Suggest } from "@blueprintjs/select";
import { DateRangeInput } from "@blueprintjs/datetime";
import { zhCN } from "date-fns/locale";
import GeoMap from "./map";
import { debounce } from "@/utils";
import { TrajectoryLocIcon } from "@/utils/icons";
import { useL10n } from "@/l10n";

interface OptionItem {
    id: string;
    name: string;
}

interface PersonSuggestProps {
    onSelected?: (selectedItem: string | null) => void;
    defaultValue?: string | null;
}

function PersonSuggest({ onSelected, defaultValue = '' }: PersonSuggestProps) {
    const { t } = useL10n();
    const [items, setItems] = useState<OptionItem[]>([]);
    const [loading, setLoading] = useState<boolean>(false);
    const [selectedItem, setSelectedItem] = useState<OptionItem | null>(defaultValue ? {id: defaultValue, name: defaultValue} : null);

    const renderItem: ItemRenderer<OptionItem> = useCallback((item, rendererProps) => {
        if (!rendererProps.modifiers.matchesPredicate) {
            return null;
        }
        return (
            <MenuItem
                key={item.id}
                text={item.name}
                roleStructure="listoption"
                selected={item === selectedItem}
                active={rendererProps.modifiers.active}
                onClick={rendererProps.handleClick}
            />
        );
    }, [selectedItem]);

    const handleValueChange = (selectItem: OptionItem) => {
        setSelectedItem(selectItem);
        onSelected?.(selectItem?.name || '');
    };

    const fetchOptions = useCallback((queryStr: string = "") => {
        setLoading(true);
        try {
            // 模拟请求后端接口，可以带上当前的 query 条件
            setTimeout(() => {
                let data:[OptionItem] = [];
                for (let i = 1; i < 100; i++) {
                    data.push({id: `person${i}`, name: `Person${i}`});
                }
                const filtered = queryStr ? data.filter(item => item.name.toLowerCase().includes(queryStr)) : data;
                console.log('>> filtered: ', filtered.length);
                setItems(filtered);
                setLoading(false);
            }, 500)
        } catch (err) {
            console.error("加载列表失败", err);
        }
    }, []);

    const queryFetchOptions = useRef(debounce(function(text: string) {
        fetchOptions(text);
    }, 300));

    useEffect(() => {
        // 组件卸载取消防抖，防止内存泄漏
        return () => {
            queryFetchOptions.current.cancel();
        };
    }, []);

    return (
        <Suggest<OptionItem>
            closeOnSelect={true}
            fill={true}
            inputProps={{placeholder: t('搜索选择对象')}}
            items={items}
            itemRenderer={renderItem}
            inputValueRenderer={(item: OptionItem) => (item.name || '')}
            // 禁用前端二次过滤（交由服务端控制）
            itemPredicate={() => true}
            onItemSelect={handleValueChange}
            selectedItem={selectedItem}
            popoverProps={{
                matchTargetWidth: true,
                minimal: true,
                onOpening: () => {
                    // 仅当目前列表为空时才在聚焦展开时自动加载
                    if (items.length === 0) {
                        fetchOptions();
                    }
                }
            }}
            // 2. 监听输入框打字，实时搜索
            onQueryChange={(query) => {
                queryFetchOptions.current(query);
            }}
            noResults={
                loading ? (
                    <div style={{ padding: "10px", textAlign: "center" }}>
                        <Spinner size={SpinnerSize.SMALL} />
                    </div>
                ) : (
                    <NonIdealState
                        icon="search"
                        iconSize={NonIdealStateIconSize.EXTRA_SMALL}
                        description={t('未匹配到任何对象')}
                    />
                )
            }
        />
    );
}

/**
 * 人员轨迹定位。
 * 指定对象ID + 时间段， 查询并可视化其轨迹路线，按数据源用不同的图标展示，
 * 时间倒序列表展示详情。
 */
export default function PersonTrajectoryLocation() {
    const { t } = useL10n();

    return (
        <div className="map-app-panel">
            <div className="map-app-header">
                <div className="map-app-header-icon">
                    <TrajectoryLocIcon />
                </div>
                <div className="map-app-header-title">{t('人员轨迹定位')}</div>
            </div>

            <div className="map-app-main relative">
                <div className="absolute inset-0">
                    <GeoMap />

                    <div className="absolute map-app-search-panel" style={{left: "1rem", top: "1rem", width: "260px", zIndex: 10}}>
                        <Card elevation={Elevation.TWO}>
                            <h3 style={{marginTop:0}}>{t('人员轨迹查询')}</h3>
                            <form>
                            <FormGroup label={t('选择对象')}>
                                <PersonSuggest />
                            </FormGroup>

                            <FormGroup label={t('选择时间范围')}>
                                <DateRangeInput
                                    dateFnsFormat="yyyy-MM-dd"
                                    startInputProps={{ placeholder: t('开始时间') }}
                                    endInputProps={{ placeholder: t('结束时间') }}
                                    locale={zhCN}
                                />
                            </FormGroup>

                            <div>
                                <Button text={t('查询')} intent="primary" fill={true}/>
                            </div>
                            </form>
                        </Card>
                    </div>
                </div>
            </div>
        </div>
    );
}