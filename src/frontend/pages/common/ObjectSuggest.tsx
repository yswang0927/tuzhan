import { useEffect, useState, useCallback, useRef } from "react";
import {
    MenuItem,
    NonIdealState,
    NonIdealStateIconSize,
    Spinner,
    SpinnerSize,
} from "@blueprintjs/core";
import { type ItemRenderer, Suggest } from "@blueprintjs/select";

import { getJson } from "@/utils/api";
import { debounce } from "@/utils";
import { useL10n } from "@/l10n";

export interface OptionItem {
    id: string;
    name: string;
}

export interface ObjectSuggestProps {
    onSelected?: (selectedItem: string | null) => void;
    defaultValue?: string | null;
}

export function ObjectSuggest({ onSelected, defaultValue = '' }: ObjectSuggestProps) {
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

        getJson(`/api/trajectory/objects`, {obj: queryStr})
            .then(data => {
                if (Array.isArray(data)) {
                    setItems(data.map(objId => {return {id: objId, name: objId}}));
                }
            })
            .catch(err => {
                // TODO Toast提示
            }).finally(() => {
               setLoading(false);
            });
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