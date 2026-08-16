from pathlib import Path

from fastapi import APIRouter, Form, Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates

from app.config import FUZZY_MATCH_THRESHOLD
from app import merge_service

router = APIRouter(tags=["web"])

TEMPLATES_DIR = Path(__file__).resolve().parent.parent / "templates"
templates = Jinja2Templates(directory=str(TEMPLATES_DIR))


@router.get("/", response_class=HTMLResponse)
def index(request: Request):
    exact_groups = merge_service.find_exact_duplicate_groups()
    similar = merge_service.find_similar_candidates()
    low_freq = merge_service.list_task_texts(order_by_frequency_asc=True)
    history = merge_service.list_merge_history()
    return templates.TemplateResponse(
        "index.html",
        {
            "request": request,
            "exact_count": len(exact_groups),
            "similar_count": len(similar),
            "task_count": len(low_freq),
            "history_count": len(history),
        },
    )


@router.get("/merge/exact", response_class=HTMLResponse)
def exact_duplicates_page(request: Request):
    groups = merge_service.find_exact_duplicate_groups()
    return templates.TemplateResponse(
        "exact_duplicates.html", {"request": request, "groups": groups}
    )


@router.post("/merge/exact/confirm", response_class=HTMLResponse)
def exact_duplicates_confirm(
    request: Request,
    variant_texts: list[str] = Form(...),
    target_text: str = Form(...),
):
    merge_service.merge_texts(variant_texts, target_text)
    groups = merge_service.find_exact_duplicate_groups()
    return templates.TemplateResponse(
        "partials/exact_groups_list.html", {"request": request, "groups": groups}
    )


@router.get("/merge/similar", response_class=HTMLResponse)
def similar_candidates_page(request: Request, threshold: int = FUZZY_MATCH_THRESHOLD):
    candidates = merge_service.find_similar_candidates(threshold=threshold)
    return templates.TemplateResponse(
        "similar_candidates.html",
        {"request": request, "candidates": candidates, "threshold": threshold},
    )


@router.post("/merge/similar/confirm", response_class=HTMLResponse)
def similar_candidates_confirm(
    request: Request,
    text_a: str = Form(...),
    text_b: str = Form(...),
    target_choice: str = Form(...),
    target_text_custom: str = Form(""),
    threshold: int = Form(FUZZY_MATCH_THRESHOLD),
):
    target_text = (
        target_text_custom if target_choice == "custom" else
        text_a if target_choice == "a" else text_b
    )
    merge_service.merge_texts([text_a, text_b], target_text)
    candidates = merge_service.find_similar_candidates(threshold=threshold)
    return templates.TemplateResponse(
        "partials/similar_candidates_list.html",
        {"request": request, "candidates": candidates, "threshold": threshold},
    )


@router.get("/merge/manual", response_class=HTMLResponse)
def manual_merge_page(request: Request):
    tasks = merge_service.list_task_texts()
    return templates.TemplateResponse(
        "manual_merge.html", {"request": request, "tasks": tasks}
    )


@router.post("/merge/manual/confirm", response_class=HTMLResponse)
def manual_merge_confirm(
    request: Request,
    selected_texts: list[str] = Form(...),
    target_mode: str = Form(...),
    existing_target: str = Form(""),
    new_target: str = Form(""),
):
    target_text = new_target if target_mode == "new" else existing_target
    affected = merge_service.merge_texts(selected_texts, target_text)
    tasks = merge_service.list_task_texts()
    return templates.TemplateResponse(
        "manual_merge.html",
        {
            "request": request,
            "tasks": tasks,
            "message": f'"{target_text}" に {affected} 件を統合しました。',
        },
    )


@router.get("/merge/low-frequency", response_class=HTMLResponse)
def low_frequency_page(request: Request):
    tasks = merge_service.list_task_texts(order_by_frequency_asc=True)
    return templates.TemplateResponse(
        "low_frequency.html", {"request": request, "tasks": tasks}
    )


@router.get("/merge/history", response_class=HTMLResponse)
def merge_history_page(request: Request):
    history = merge_service.list_merge_history()
    return templates.TemplateResponse(
        "merge_history.html", {"request": request, "history": history}
    )
